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
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger

final class PrintToBox {
    private static final String VERSION = '2.0'
    private static final String CONFIG_FILE = '/etc/PrintToBox.conf'
    private static final String TOKENS_FILE = '/var/cache/PrintToBox/tokens'

    static void main(String[] args) {
        BoxAPIConnection api
        BoxUser.Info userInfo
        BoxFolder rootFolder
        BoxFolder collaborationFolder
        BoxFolder printFolder
        long totalSize = 0l
        def cli
        def cmdLineOpts
        def configOpts
        def files = [:]
        def tokens
        def tokensFile
        FileLock tokensLock
        RandomAccessFile tokensRAF
        String folderName
        String userName
        String AUTH_CODE = ''

        // Turn off logging to prevent polluting the output.
        Logger.getLogger("com.box.sdk").setLevel(Level.OFF);
        cli = new CliBuilder(usage: """
PrintToBox [<options>] <username> <filename> [<filename 2>...]

Upload files to a Box.com collaborated folder of which <username> is
the owner. Creates the collaborated folder and any subfolder[s] if they
do not exist. By default, it uploads a new version for existing files.

""", header: 'Options:')

        cli.a(longOpt:'auth-code', args: 1, argName:'auth_code', 'Auth code from OAUTH2 leg one')
        cli.d(longOpt:'differ', 'Upload new version only if the file differs')
        cli.D(longOpt:'debug', 'Enable debugging')
        cli.f(longOpt:'folder', args: 1, argName:'folder', 'Box folder path. Top-level should be unique. Default: "PrintToBox <username>"')
        cli.h(longOpt:'help', 'Print this help text')
        cli.R(longOpt:'replace', 'If the filename already exists in Box, delete it (and all versions) and replace it with this file')
        cli.T(longOpt:'total-size', 'Abort if total size of file set exceeds storage in Box. May not make sense with --replace, --differ, or --no-update')
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
        cmdLineOpts.arguments()[1..-1].each { k ->
            files[k] = [:]
        }

        if (cmdLineOpts.a)
            AUTH_CODE = cmdLineOpts.a

        try {
            configOpts = new ConfigHelper(CONFIG_FILE)
        } catch (e) {
            if (cmdLineOpts.D) e.printStackTrace()
            return
        }

        if (cmdLineOpts.f) {
            folderName = cmdLineOpts.f
        } else if (configOpts.baseFolderName) {
            folderName = configOpts.baseFolderName + ' ' + userName
        } else {
            folderName = 'PrintToBox ' + userName
        }

        try {
            tokensFile = new TokensFileHelper(TOKENS_FILE, (Integer) configOpts.tokensLockRetries)
            tokens = tokensFile.getTokens()
        } catch (e) {
            if (cmdLineOpts.D) e.printStackTrace()
            return
        }

        if (tokens.accessToken == null && tokens.refreshToken == null && AUTH_CODE.isEmpty()) {
            println """Error: Either '${TOKENS_FILE}' is inaccessible (file permissions)
or OAUTH2 is not set up. If the tokens file is accessible, either supply
the authorization code from leg one of OAUTH2 or set up the tokens file
manually."""
            tokensFile.close()
            return
        }

        try {
            totalSize = setFilesProperties(files, cmdLineOpts."differ")

            api = getAPI(configOpts, AUTH_CODE, tokens)

            updateTokens(api, tokensRAF, tokens)

            userInfo            = getAPIUserInfo(api)
            rootFolder          = getRootFolder(api)
            collaborationFolder = getCollaborationFolder(rootFolder, folderName)

            setupCollaboration(collaborationFolder, userInfo, userName, (String) configOpts.enterpriseDomain)

            printFolder = getUploadFolder(collaborationFolder, folderName)

            if (cmdLineOpts."total-size" && files.size() > 1) {
                checkUploadSize(printFolder, totalSize)
            }

            uploadFiles(files, printFolder, cmdLineOpts)

        } finally {
            tokensFile.close()
        }
    } //end main()

    private static long setFilesProperties(files, differ) {

        long totalSize = 0l

        try {
            files.each { fileName, fileProperties ->
                File file = new File((String)fileName)
                long fileSize = file.length()
                totalSize += fileSize
                FileInputStream fileStream = new FileInputStream(file)

                if (differ) {
                    MessageDigest sha = MessageDigest.getInstance("SHA1");
                    DigestInputStream digestInputStream = new DigestInputStream(fileStream, sha);
                    byte[] b = new byte[32768]
                    while (digestInputStream.read(b) != -1) ;
                    fileProperties.SHA1 = sprintf("%040x", new BigInteger(1, sha.digest()))
                    fileStream = new FileInputStream(file)
                }

                fileProperties.file = file
                fileProperties.size = fileSize
                fileProperties.stream = fileStream
            }

            return totalSize

        } catch (FileNotFoundException e) {
            println e.getMessage()
        }
    }

    private static BoxAPIConnection getAPI(configOpts, String authCode, tokens) {

        BoxAPIConnection api

        try {
            if (!authCode.isEmpty()) {
                api = new BoxAPIConnection(
                        (String) configOpts.clientId,
                        (String) configOpts.clientSecret,
                        authCode)
            } else {
                api = new BoxAPIConnection(
                        (String) configOpts.clientId,
                        (String) configOpts.clientSecret,
                        (String) tokens.accessToken,
                        (String) tokens.refreshToken)
            }
        } catch (BoxAPIException e) {
            println """Error: Could not connect to Box API. Usually, this means one of:
1) ${CONFIG_FILE} is not configured correctly
2) ${TOKENS_FILE} has expired tokens and OAUTH2 leg 1 needs to be re-run
"""
            println boxErrorMessage(e)
        }
    }

    private static void updateTokens(BoxAPIConnection api, RandomAccessFile tokensRAF, tokens) {
        try {
            tokens.accessToken = api.getAccessToken()
            tokens.refreshToken = api.getRefreshToken()

            if (tokens.accessToken != null && tokens.refreshToken != null)
                writeTokensToFile(tokensRAF, tokens);

        } catch (BoxAPIException e) {
            println """Error: Could not get new tokens. Most likely, ${TOKENS_FILE}
has expired tokens and OAUTH2 leg 1 needs to be re-run"""
            println boxErrorMessage(e)
        } catch (e) {
            println 'Error: Could not get new tokens and write them to disk'
            println e.toString()
        }
    }

    private static BoxUser.Info getAPIUserInfo(BoxAPIConnection api) {

        BoxUser.Info userInfo

        try {
            userInfo = BoxUser.getCurrentUser(api).getInfo()
            return userInfo

        } catch (BoxAPIException e) {
            println 'Error: Could not access the service account user via the API'
            println boxErrorMessage(e)
        }
    }

    private static BoxFolder getRootFolder(BoxAPIConnection api) {

        BoxFolder rootFolder

        try {
            rootFolder = BoxFolder.getRootFolder(api)
            return rootFolder

        } catch (BoxAPIException e) {
            println 'Error: Could not get the service account root folder'
            println boxErrorMessage(e)
        }
    }

    private static BoxFolder getCollaborationFolder(BoxFolder rootFolder, String folderName) {

        BoxFolder collaborationFolder
        File folderFileObj = new File(folderName)
        String collaborationFolderName = folderName

        try {
            if (folderFileObj.getParent() != null) {
                collaborationFolderName = folderFileObj.toPath().getName(0).toString()
            }

            collaborationFolder = getFolder(rootFolder, collaborationFolderName)

            return collaborationFolder

        } catch (BoxAPIException e) {
            println 'Error: Could not create or access the target folder'
            println boxErrorMessage(e)
        } catch (e) {
            println 'Error: Could not create or access the target folder'
            println e.toString()
        }
    }

    private static BoxFolder getUploadFolder(BoxFolder collaborationFolder, String folderName) {

        BoxFolder uploadFolder = collaborationFolder
        File folderFileObj = new File(folderName)

        try {
            if (folderFileObj.getParent() != null) {

                Path folderPath = folderFileObj.toPath()

                //Normally, you would think the subpath is "getNameCount() - 1" but as the Javadoc says,
                // "endIndex - the index of the last element, exclusive". So, we nuke the "- 1" to make it
                // pull in the final element.

                folderPath.subpath(1, folderPath.getNameCount()).each({
                    uploadFolder = getFolder(uploadFolder, it.toString())
                })
            }

            return uploadFolder

        } catch (BoxAPIException e) {
            println 'Error: Box API could not retrieve or create the subfolders'
            println boxErrorMessage(e)
        } catch (e) {
            println 'Error: System could not retrieve or create the subfolders'
            println e.toString()
        }
    }

    private static void checkUploadSize(BoxFolder uploadFolder, long totalSize) {
        try {
            uploadFolder.canUpload('PrintToBox' + new Date().toString() + new Date().toString() + 'PrintToBox', totalSize)
        } catch (BoxAPIException e) {
            println 'Error: Total size of the file set is too large'
            println boxErrorMessage(e)
        }
    }

    private static void uploadFiles(files, BoxFolder uploadFolder, cmdLineOpts) {
        try {
            files.each { fileName, fileProperties ->
                uploadFileToFolder(
                        uploadFolder,
                        (FileInputStream) fileProperties.stream,
                        (String) fileProperties.file.getName(),
                        (long) fileProperties.size,
                        (String) fileProperties.SHA1,
                        cmdLineOpts)
            }

        } catch (BoxAPIException e) {
            println 'Error: Box API could not upload the file to the target folder'
            println boxErrorMessage(e)
        } catch (e) {
            println 'Error: System could not upload the file to the target folder'
            println e.toString()
        }
    }

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
    private static void setupCollaboration(BoxFolder folder, BoxUser.Info myId, String user, String enterpriseDomain) {

        Boolean collaborations_exist = false
        String userName = user + enterpriseDomain

        try {
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
        } catch (BoxAPIException e) {
            println """Error: Could not properly set collaboration on the folder:
1) Check that user '${userName}' exists in Box.
2) Check that enterpriseDomain '${enterpriseDomain}' is correct
3) Ensure the user does not have a folder '${folder.getInfo().getName()}' already"""
            println boxErrorMessage(e)
        } catch (e) {
            println 'Error: Could not set the collaboration on the target folder'
            println e.toString()
        }
    }
}
