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
import java.nio.file.Path
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger

final class PrintToBox {
    private static final String VERSION = '1.3'
    private static final String CONFIG_FILE = '/etc/PrintToBox.conf'
    private static final String TOKENS_FILE = '/var/cache/PrintToBox/tokens'

    static void main(String[] args) {
        BoxAPIConnection api
        BoxUser.Info userInfo
        BoxFolder rootFolder
        BoxFolder collaborationFolder
        BoxFolder printFolder
        File file
        long fileSize
        FileInputStream fileStream
        def cli
        def cmdLineOpts
        def slurpOpts
        def configOpts = [:]
        def tokens
        FileLock tokensLock
        RandomAccessFile tokensRAF
        String folderName
        String collaborationFolderName
        String userName
        String fileName
        String fileSHA1 = ''
        String AUTH_CODE = ''

        // Turn off logging to prevent polluting the output.
        Logger.getLogger("com.box.sdk").setLevel(Level.OFF);
        cli = new CliBuilder(usage: """
PrintToBox [<options>] <username> <filename>

Upload <filename> to a Box.com collaborated folder of which <username> is
the owner. Creates the collaborated folder and any subfolder[s] if they
do not exist.

""", header: 'Options:')

        cli.a(longOpt:'auth-code', args: 1, argName:'auth_code', 'Auth code from OAUTH2 leg one')
        cli.d(longOpt:'differ', 'Upload new version only if the file differs')
        cli.f(longOpt:'folder', args: 1, argName:'folder', 'Box folder path. Top-level should be unique. Default: "PrintToBox <username>"')
        cli.h(longOpt:'help', 'Print this help text')
        cli.R(longOpt:'replace', 'If the filename already exists in Box, delete it (and all versions) and replace it with this file')
        cli.U(longOpt:'no-update', 'If the filename already exists in Box, do nothing')
        cli.V(longOpt:'version', 'Display the program version and exit')

        cmdLineOpts = cli.parse(args)

        if (cmdLineOpts.V) {
            println 'PrintToBox ' + VERSION
            return
        }

        if (cmdLineOpts.h || cmdLineOpts.arguments().size() < 2) {
            cli.usage()
            return
        }

        if (cmdLineOpts.R && cmdLineOpts.U) {
            println 'Error: -R/--replace and -U/--no-update are mutually exclusive options. See --help for details.'
            return
        }

        userName = cmdLineOpts.arguments()[0]
        fileName = cmdLineOpts.arguments()[1]

        if (cmdLineOpts.a)
            AUTH_CODE = cmdLineOpts.a

        try {
            //The LAX parser is the only one that supports comments (/* */) in JSON
            //However, it returns a horrible map type. Convert it here to a normal Groovy map.
            slurpOpts = new JsonSlurper().setType(JsonParserType.LAX).parse(new File(CONFIG_FILE))
            slurpOpts.each {k, v -> configOpts.put(k, slurpOpts.get(k))}

            assert configOpts.clientId instanceof String
            assert configOpts.clientSecret instanceof String
            assert configOpts.enterpriseDomain instanceof String
            assert (!configOpts.tokensLockRetries || configOpts.tokensLockRetries instanceof Integer)
            assert (!configOpts.baseFolderName || configOpts.baseFolderName instanceof String)

        } catch (AssertionError e) {
            println 'Error: Invalid config file: ' + """${CONFIG_FILE}
""" + 'Expected format (JSON):' + """
{
  "enterpriseDomain": "@example.com",
  "clientId": "abcdefghijklmnopqrstuvwxyz123456",
  "clientSecret": "abcdefghijklmnopqrstuvwxyz123456"
}

Optional keys:
  "tokensLockRetries": 1000 (Default)
  "baseFolderName": "PrintToBox" (Default)"""

            return
        } catch (e) {
            println e.toString()
            println e.getCause().toString()
            return
        }

        if (!configOpts.tokensLockRetries)
            configOpts.tokensLockRetries = 1000

        if (cmdLineOpts.f) {
            folderName = cmdLineOpts.f
        } else if (configOpts.baseFolderName) {
            folderName = configOpts.baseFolderName + ' ' + userName
        } else {
            folderName = 'PrintToBox ' + userName
        }

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

            tokens = new JsonSlurper().parse(buf)

            assert tokens.accessToken instanceof String
            assert tokens.refreshToken instanceof String
        } catch (AssertionError e) {
            println 'Error: Invalid tokens file: ' + """${TOKENS_FILE}
""" + 'Expected format (JSON):' + """
{
  "accessToken": "abcdefghijklmnopqrstuvwxyz123456",
  "refreshToken": "abcdefghijklmnopqrstuvwxyz123456"
}"""
            tokensLock.release()
            tokensRAF.close()
            return
        } catch (e) {
            // Do nothing, probably FileNotFound
            tokens = [accessToken: null, refreshToken: null]
        }

        try {
            file = new File(fileName)
            fileSize = file.length()
            fileStream = new FileInputStream(file)

            if (cmdLineOpts."differ") {
                fileSHA1 = new BigInteger(1, MessageDigest.getInstance("SHA1").digest(fileStream.getBytes())).toString(16)
                fileStream = new FileInputStream(file)
            }

        } catch (FileNotFoundException e) {
            println e.getMessage()
            //If the tokens file is inaccessible due to permissions, these are null
            if (tokensLock != null) tokensLock.release();
            if (tokensRAF != null) tokensRAF.close()
            return
        }

        if (tokens.accessToken == null && tokens.refreshToken == null && AUTH_CODE.isEmpty()) {
            println """Error: Either '${TOKENS_FILE}' is inaccessible (file permissions)
or OAUTH2 is not set up. If the tokens file is accessible, either supply
the authorization code from leg one of OAUTH2 or set up the tokens file
manually."""
            //If the tokens file is inaccessible due to permissions, these are null
            if (tokensLock != null) tokensLock.release();
            if (tokensRAF != null) tokensRAF.close();
            return
        }

        try {
            if (!AUTH_CODE.isEmpty()) {
                api = new BoxAPIConnection(configOpts.clientId, configOpts.clientSecret, AUTH_CODE)
            } else {
                api = new BoxAPIConnection(configOpts.clientId, configOpts.clientSecret, tokens.accessToken, tokens.refreshToken)
            }
        } catch (BoxAPIException e) {
            println """Error: Could not connect to Box API. Usually, this means one of:
1) ${CONFIG_FILE} is not configured correctly
2) ${TOKENS_FILE} has expired tokens and OAUTH2 leg 1 needs to be re-run
"""
            println boxErrorMessage(e)

            tokensLock.release()
            tokensRAF.close()

            return
        }

        try {
            tokens.accessToken = api.getAccessToken()
            tokens.refreshToken = api.getRefreshToken()

            if (tokens.accessToken != null && tokens.refreshToken != null)
                writeTokensToFile(tokensRAF, tokens);

        } catch (BoxAPIException e) {
            println """Error: Could not get new tokens. Most likely, ${TOKENS_FILE}
has expired tokens and OAUTH2 leg 1 needs to be re-run"""
            println boxErrorMessage(e)

            tokensLock.release()
            tokensRAF.close()

            return
        } catch (e) {
            println 'Error: Could not get new tokens and write them to disk'
            println e.toString()

            tokensLock.release()
            tokensRAF.close()

            return
        }

        try {
            userInfo = BoxUser.getCurrentUser(api).getInfo()
            rootFolder = BoxFolder.getRootFolder(api)

        } catch (BoxAPIException e) {
            println 'Error: Could not access the service account user via the API or get its root folder'
            println boxErrorMessage(e)

            tokensLock.release()
            tokensRAF.close()

            return
        }

        File folder = new File(folderName)
        if (folder.getParent() != null) {
            collaborationFolderName = folder.toPath().getName(0).toString()
        } else {
            collaborationFolderName = folderName
        }

        try {
            collaborationFolder = getFolder(rootFolder, collaborationFolderName)
        } catch (BoxAPIException e) {
            println 'Error: Could not create or access the target folder'
            println boxErrorMessage(e)

            tokensLock.release()
            tokensRAF.close()

            return
        } catch (e) {
            println 'Error: Could not create or access the target folder'
            println e.toString()
            tokensLock.release()
            tokensRAF.close()

            return
        }

        try {
            setupCollaboration(collaborationFolder, userInfo, userName + configOpts.enterpriseDomain)

        } catch (BoxAPIException e) {
            println """Error: Could not properly set collaboration on the folder:
1) Check that user '${userName}' exists in Box.
2) Check that enterpriseDomain '${configOpts.enterpriseDomain}' is correct
3) Ensure the user does not have a folder '${collaborationFolder.getInfo().getName()}' already"""
            println boxErrorMessage(e)

            tokensLock.release()
            tokensRAF.close()

            return
        } catch (e) {
            println 'Error: Could not set the collaboration on the target folder'
            println e.toString()
            tokensLock.release()
            tokensRAF.close()

            return
        }

        try {
            printFolder = collaborationFolder

            if (folder.getParent() != null) {

                Path folderPath = folder.toPath()

                //If Path is CollabFolder/SubFolder then just create SubFolder because Java will not
                // allow subpaths of length = 1 so the else{} fails in this case
                //Shrink path to reflect that top-level is collaborationFolder rather than root folder
                // because we know collaborationFolder already exists (probably...)

                if (folderPath.getNameCount() == 2) {
                    printFolder = getFolder(printFolder, folderPath.getName(1).toString())
                } else {
                    folderPath.subpath(1, folderPath.getNameCount() - 1).each({
                        printFolder = getFolder(printFolder, it.toString())
                    })
                }
            }
        } catch (BoxAPIException e) {
            println 'Error: Box API could not retrieve or create the subfolders'
            println boxErrorMessage(e)

            tokensLock.release()
            tokensRAF.close()

            return
        } catch (e) {
            println 'Error: System could not retrieve or create the subfolders'
            println e.toString()
            tokensLock.release()
            tokensRAF.close()

            return
        }

        try {
            uploadFileToFolder(printFolder, fileStream, file.getName(), fileSize, fileSHA1, cmdLineOpts)

        } catch (BoxAPIException e) {
            println 'Error: Box API could not upload the file to the target folder'
            println boxErrorMessage(e)
        } catch (e) {
            println 'Error: System could not upload the file to the target folder'
            println e.toString()
        } finally {
            tokensLock.release()
            tokensRAF.close()
        }
    } //end main()

    private static void uploadFileToFolder(BoxFolder folder, InputStream fileStream, String fileName, long fileSize, String fileSHA1, cmdLineOpts) {

        // By definition, there is an explicit race condition on checking if a file exists and then
        // uploading afterward. This is how Box works. There is no way to atomically send a file
        // and have Box rename it automatically if there is a conflict.
        //
        //For each item in the root folder:
        // If --no-update is set and it's a file and it is named the same thing
        //     then return
        // If --differ is set and it's a file and named the same and the SHA1 hash is equivalent
        //     then return
        // If --replace is set and it's a file and named the same then delete the old one,
        //     upload the new one and return
        // If it's a file and it is named the same thing, upload a new version of that file
        //     and return
        // If it's a folder and it is named the same thing, name the upload "file + TODAY"
        //     and return
        //
        // Otherwise, upload the file to the root folder

        for (BoxItem.Info itemInfo : folder) {
            if (cmdLineOpts."no-update" && itemInfo instanceof BoxFile.Info && itemInfo.getName() == fileName) {
                return
            } else if (cmdLineOpts."differ" && itemInfo instanceof BoxFile.Info && itemInfo.getName() == fileName &&
                       itemInfo.getSha1() == fileSHA1) {
                return
            } else if (cmdLineOpts."replace" && itemInfo instanceof BoxFile.Info && itemInfo.getName() == fileName) {
                //Use canUploadVersion() because folder.canUpload() will return a filename conflict
                itemInfo.getResource().canUploadVersion(fileName, fileSize, folder.getID())
                itemInfo.getResource().delete()
                folder.uploadFile(fileStream, fileName)
                return
            } else if (itemInfo instanceof BoxFile.Info && itemInfo.getName() == fileName) {
                itemInfo.getResource().canUploadVersion(fileName, fileSize, folder.getID())
                itemInfo.getResource().uploadVersion(fileStream)
                return
            } else if (itemInfo instanceof BoxFolder.Info && itemInfo.getName() == fileName) {
                String newFileName = fileName + ' ' + new Date()
                folder.canUpload(newFileName, fileSize)
                folder.uploadFile(fileStream, newFileName)
                return
            }
        }
        folder.canUpload(fileName, fileSize)
        folder.uploadFile(fileStream, fileName)
    }

    private static String boxErrorMessage(BoxAPIException boxAPIException) {
        def retval = boxAPIException.toString()
        def resp = boxAPIException.getResponse()

        if (resp != null) {
            retval += "\n" + JsonOutput.prettyPrint(resp)
        } else {
            retval += "\n" + boxAPIException.getLocalizedMessage()
        }

        return retval
    }

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
            BoxUser.Info boxCreatorInfo = itemInfo.getCreatedBy()
            BoxCollaboration.Role boxCollaborationRole = itemInfo.getRole()
            BoxCollaboration.Status boxCollaborationStatus = itemInfo.getStatus()

            //If pending collaboration, decide what to do
            //Else, if not a user-type collaboration, skip it (not handling groups)
            if (boxCollaborationStatus == BoxCollaboration.Status.PENDING &&
                    boxCollaborationRole == BoxCollaboration.Role.EDITOR &&
                    boxCreatorInfo.getID() == myId.getID() &&
                    boxCollaboratorInfo == null
            ) {
                println """Warning: User '${userName}' does not appear to exist and the
collaboration on folder '${folder.getInfo().getName()}' appears stuck
in Pending status. The file is likely to be uploaded correctly but the
usage will be charged to the service account and no other account may
be able to view the folder."""
                continue

            } else if (!(boxCollaboratorInfo instanceof BoxUser.Info)) {
                continue
            }

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
