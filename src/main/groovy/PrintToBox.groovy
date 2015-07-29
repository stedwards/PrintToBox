import com.box.sdk.BoxAPIConnection
import com.box.sdk.BoxAPIException
import com.box.sdk.BoxCollaboration
import com.box.sdk.BoxCollaborator
import com.box.sdk.BoxFile
import com.box.sdk.BoxFolder
import com.box.sdk.BoxItem
import com.box.sdk.BoxUser
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.json.JsonParserType
import java.nio.channels.FileLock
import java.util.logging.Level
import java.util.logging.Logger

final class PrintToBox {
    private static final String CONFIG_FILE = '/etc/PrintToBox.conf'
    private static final String TOKENS_FILE = '/var/cache/PrintToBox/tokens'

    static void main(String[] args) {
        BoxAPIConnection api
        def cli
        def cmdLineOpts
        def configOpts
        def tokens
        def jsonSlurper = new JsonSlurper()
        FileLock tokensLock
        RandomAccessFile tokensRAF
        String userName
        String fileName
        String AUTH_CODE = ''
        String folderName = 'PrintToBox ' + userName

        // Turn off logging to prevent polluting the output.
        Logger.getLogger("com.box.sdk").setLevel(Level.OFF);
        cli = new CliBuilder(usage: """
PrintToBox [<options>] <username> <filename>

Upload <filename> to a Box.com collaborated folder of which <username> is
the owner. Creates the folder if it doesn't exist.

""", header: 'Options:')

        cli.a(args: 1, argName:'auth_code', 'Auth code from OAUTH2 leg one')
        cli.f(args: 1, argName:'folder', 'Box folder name. Should be unique per user. Default: "PrintToBox <username>"')
        cmdLineOpts = cli.parse(args)


        if (cmdLineOpts.arguments().size() < 2) {
            cli.usage()
            return
        }

        userName = cmdLineOpts.arguments()[0]
        fileName = cmdLineOpts.arguments()[1]

        if (cmdLineOpts.a)
            AUTH_CODE = cmdLineOpts.a

        try {
            configOpts = jsonSlurper.setType(JsonParserType.LAX).parse(new File(CONFIG_FILE))

            assert configOpts.clientId instanceof String
            assert configOpts.clientSecret instanceof String
            assert configOpts.enterpriseDomain instanceof String
            assert (configOpts.tokensLockRetries == null || configOpts.tokensLockRetries instanceof Integer)
            assert (configOpts.baseFolderName == null || configOpts.baseFolderName instanceof String)

        } catch (AssertionError e) {
            println('Error: Invalid config file: ' + """${CONFIG_FILE}
""" + 'Expected format (JSON):' + """
{
  "enterpriseDomain": "@example.com",
  "clientId": "abcdefghijklmnopqrstuvwxyz123456",
  "clientSecret": "abcdefghijklmnopqrstuvwxyz123456"
}

Optional keys:
  "tokensLockRetries": 1000 (Default)
  "baseFolderName": "PrintToBox"
"""
            )
            return
        } catch (e) {
            println(e.toString())
            println(e.getCause().toString())
            return
        }

        configOpts.tokensLockRetries = configOpts.tokensLockRetries ?: 1000
        folderName = configOpts.baseFolderName ? configOpts.baseFolderName + ' ' + userName: folderName

        if (cmdLineOpts.f)
            folderName = cmdLineOpts.f


        try {
            tokensRAF = new RandomAccessFile(TOKENS_FILE, "rw");

            //The program takes about 6 wall seconds to complete. Depending on the random sleep times chosen,
            //1000 loops gives the program between 16 and 100 minutes to complete, but probably ~ 1 hour.
            (1..configOpts.tokensLockRetries).find {
                tokensLock = tokensRAF.getChannel().tryLock()
                if (tokensLock == null) {
                    Random r = new Random()
                    sleep(1000 + r.nextInt(5000))
                    return false
                } else {
                    return true
                }
            }

            if (tokensLock == null) {
                println "Error: Cannot lock tokens file after ${configOpts.tokensLockRetries} tries. " +
                        'Consider setting the ("tokensLockRetries": 1234) option in the config file.'
                tokensRAF.close()
                return
            }

            //Can't use an InputStream on the channel because it releases the lock and closes the file handle
            byte[] buf = new byte[tokensRAF.length().toInteger()]

            int bytes_read = tokensRAF.read(buf, 0, tokensRAF.length().toInteger())

            tokens = jsonSlurper.parse(buf)

            assert tokens.accessToken instanceof String
            assert tokens.refreshToken instanceof String
        } catch (AssertionError e) {
            println('Error: Invalid tokens file: ' + """${TOKENS_FILE}
""" + 'Expected format (JSON):' + """
{
  "accessToken": "abcdefghijklmnopqrstuvwxyz123456",
  "refreshToken": "abcdefghijklmnopqrstuvwxyz123456"
}
"""
            )
            tokensLock.release()
            tokensRAF.close()
            return
        } catch (e) {
            // Do nothing, probably FileNotFound
            println e.getClass().toString() + e.getStackTrace().toString() + e.getMessage() + ' ' + e.getCause().toString()
            tokens = [accessToken: null, refreshToken: null]
        }

        File file = new File(fileName)
        FileInputStream fileStream
        try {
            fileStream = new FileInputStream(file)
        } catch (FileNotFoundException e) {
            println(e.getMessage())
            tokensLock.release()
            tokensRAF.close()
            return
        }

        if (tokens.accessToken == null && tokens.refreshToken == null && AUTH_CODE.isEmpty()) {
            println 'Error: OAUTH2 is not set up. Either supply the authorization code from ' +
                    'leg one of OAUTH2 or set up the tokens file manually.'
            tokensLock.release()
            tokensRAF.close()
            return
        }

        try {
            if (!AUTH_CODE.isEmpty()) {
                api = new BoxAPIConnection(configOpts.clientId, configOpts.clientSecret, AUTH_CODE)
            } else {
                api = new BoxAPIConnection(configOpts.clientId, configOpts.clientSecret, tokens.accessToken, tokens.refreshToken)
            }
            tokens.accessToken = api.getAccessToken()
            tokens.refreshToken = api.getRefreshToken()

            if (tokens.accessToken != null && tokens.refreshToken != null)
                writeTokensToFile(tokensRAF, tokens);

            BoxUser.Info userInfo = BoxUser.getCurrentUser(api).getInfo()

            BoxFolder rootFolder = BoxFolder.getRootFolder(api)

            BoxFolder printFolder = getFolder(rootFolder, folderName)

            setupCollaboration(printFolder, userInfo, userName + configOpts.enterpriseDomain)

            uploadFileToFolder(printFolder, fileStream, file.name)

        } catch (BoxAPIException e) {
            println(boxErrorMessage(e))
        } finally {
            tokensLock.release()
            tokensRAF.close()
        }
    }

    private static void uploadFileToFolder(BoxFolder folder, InputStream fileStream, String fileName) {

        // By definition, there is an explicit race condition on checking if a file exists and then
        // uploading afterward. This is how Box works. There is no way to atomically send a file
        // and have Box rename it automatically if there is a conflict.
        //
        // Because this is a "printer", we make a best effort to get the file there.
        //
        //For each item in the root folder:
        // If it's a file and it is named the same thing, upload a new version of that file
        //     and return
        // If it's a folder and it is named the same thing, name the upload "file + TODAY"
        //     and return
        //
        // Otherwise, upload the file to the root folder

        for (BoxItem.Info itemInfo : folder) {
            if (itemInfo instanceof BoxFile.Info && itemInfo.getName() == fileName) {
                itemInfo.getResource().uploadVersion(fileStream)
                return
            } else if (itemInfo instanceof BoxFolder.Info && itemInfo.getName() == fileName) {
                folder.uploadFile(fileStream, fileName + ' ' + new Date())
                return
            }
        }

        folder.uploadFile(fileStream, fileName)

    }

    private static String boxErrorMessage(BoxAPIException boxAPIException) {
        JsonSlurper jsonSlurper = new JsonSlurper()
        println(boxAPIException.toString())

        def resp = boxAPIException.getResponse()

        if (resp != null) {
            def result = jsonSlurper.parseText(resp);

            def retval = ''

            if (result.type != null)
                retval = result.type.capitalize() + ' ';

            retval.concat(result.status + ': ' + result.message)

            return retval
        }

        return boxAPIException.getLocalizedMessage()
    }

    //The service account will use top-level folders
    private static BoxFolder getFolder(BoxFolder folder, String folderName) {

        //Try to find an existing folder and return that
        for (BoxItem.Info itemInfo : folder) {

            if (itemInfo instanceof BoxFolder.Info && itemInfo.getName() == folderName) {
                BoxFolder returnFolder = (BoxFolder) itemInfo.getResource()
                return returnFolder
            }
        }

        //Give up and create a new folder and return it
        BoxFolder.Info returnFolderInfo = folder.createFolder(folderName)
        BoxFolder returnFolder = (BoxFolder) returnFolderInfo.getResource()

        return returnFolder
    }

    //Set up collaboration so that the user owns the folder but the service account can upload to it
    private static void setupCollaboration(BoxFolder folder, BoxUser.Info myId, String userName) {

        Boolean collaborations_exist = false

        //Check for existing collaboration
        for (BoxCollaboration.Info itemInfo : folder.getCollaborations()) {

            collaborations_exist = true

            BoxCollaborator.Info boxCollaboratorInfo = itemInfo.getAccessibleBy()
            BoxCollaboration.Role boxCollaborationRole = itemInfo.getRole()

            //Skip group collaborations
            if (!(boxCollaboratorInfo instanceof BoxUser.Info))
                continue;

            BoxUser.Info userInfo = (BoxUser.Info) boxCollaboratorInfo

            //If it's the service account and it's co-owner, demote to editor
            //If it's the user and the user is set to editor, make user the owner which will automatically
            // switch the service account to a collaboration editor
            if (userInfo.getID() == myId.getID() && boxCollaborationRole == BoxCollaboration.Role.CO_OWNER) {

                itemInfo.setRole(BoxCollaboration.Role.EDITOR)
                BoxCollaboration bc = itemInfo.getResource()

                //FIXME this is a workaround of an API bug. See Git issue 147.
                // https://github.com/box/box-java-sdk/issues/147
                try {
                    bc.updateInfo(itemInfo)
                } catch (ClassCastException e) {
                    //do nothing
                }

            } else if (userInfo.getLogin() == userName && boxCollaborationRole == BoxCollaboration.Role.EDITOR) {

                itemInfo.setRole(BoxCollaboration.Role.OWNER)
                BoxCollaboration bc = itemInfo.getResource()

                //FIXME this is a workaround of an API bug. See Git issue 147.
                // https://github.com/box/box-java-sdk/issues/147
                try {
                    bc.updateInfo(itemInfo)
                } catch (ClassCastException e) {
                    //do nothing
                }
            }
        }

        if (!collaborations_exist) {
            //Probably new folder just created. Create new collaboration with user as the owner.
            //This will ensure the storage is counted under the user's account.

            //This is the dance recommended by Box.com support.
            // 1. Create folder (above)
            // 2. Add user as a collaborator with editor permissions
            // 3. Set the user as an OWNER in the collaboration. This will demote service account to EDITOR
            // 4. Commit the change

            BoxCollaboration.Info newCollaborationInfo = folder.collaborate(userName, BoxCollaboration.Role.EDITOR)

            newCollaborationInfo.setRole(BoxCollaboration.Role.OWNER)

            BoxCollaboration bc = newCollaborationInfo.getResource()

            //FIXME this is a workaround of an API bug. See Git issue 147.
            // https://github.com/box/box-java-sdk/issues/147
            try {
                bc.updateInfo(newCollaborationInfo)
            } catch (ClassCastException e) {
                //do nothing
            }
        }
    }

    private static void writeTokensToFile(RandomAccessFile tokensRAF, tokens) {
        def jsonOutput = JsonOutput.toJson(tokens)
        byte[] jsonBytes = JsonOutput.prettyPrint(jsonOutput).getBytes()
        tokensRAF.seek(0)
        tokensRAF.write(jsonBytes, 0, jsonBytes.length)
    }
}
