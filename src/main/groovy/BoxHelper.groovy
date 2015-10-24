import com.box.sdk.BoxAPIConnection
import com.box.sdk.BoxAPIException
import com.box.sdk.BoxCollaboration
import com.box.sdk.BoxCollaborator
import com.box.sdk.BoxFile
import com.box.sdk.BoxFolder
import com.box.sdk.BoxItem
import com.box.sdk.BoxUser
import groovy.json.JsonOutput

import java.nio.file.Path

final class BoxHelper {

    private BoxAPIConnection api

    BoxHelper() {}

    BoxHelper(configOpts, String authCode, tokens) {
        connect(configOpts, authCode, tokens)
    }

    public void connect(configOpts, String authCode, tokens) {
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
1) ${configOpts.getConfigFileName()} is not configured correctly
2) ${tokens.getTokensFileName()} has expired tokens and OAUTH2 leg 1 needs to be re-run
"""
            println boxErrorMessage(e)
            throw e
        }
    }

    public void updateTokens(tokens) {
        try {
            tokens.accessToken = api.getAccessToken()
            tokens.refreshToken = api.getRefreshToken()
        } catch (BoxAPIException e) {
            println """Error: Could not get new tokens. Most likely, ${tokens.getTokensFileName()}
has expired tokens and OAUTH2 leg 1 needs to be re-run"""
            println boxErrorMessage(e)
            throw e
        }
    }

    public BoxUser.Info getAPIUserInfo() {

        BoxUser.Info userInfo

        try {
            userInfo = BoxUser.getCurrentUser(api).getInfo()
            return userInfo

        } catch (BoxAPIException e) {
            println 'Error: Could not access the service account user via the API'
            println boxErrorMessage(e)
            throw e
        }
    }

    public BoxFolder getRootFolder() {

        BoxFolder rootFolder

        try {
            rootFolder = BoxFolder.getRootFolder(api)
            return rootFolder

        } catch (BoxAPIException e) {
            println 'Error: Could not get the service account root folder'
            println boxErrorMessage(e)
            throw e
        }
    }

    public BoxFolder getCollaborationFolder(BoxFolder rootFolder, String folderName) {

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
            throw e
        } catch (e) {
            println 'Error: Could not create or access the target folder'
            println e.toString()
            throw e
        }
    }

    public BoxFolder getUploadFolder(BoxFolder collaborationFolder, String folderName) {

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
            throw e
        } catch (e) {
            println 'Error: System could not retrieve or create the subfolders'
            println e.toString()
            throw e
        }
    }

    public void checkUploadSize(BoxFolder uploadFolder, long totalSize) {
        try {
            uploadFolder.canUpload('PrintToBox' + new Date().toString() + new Date().toString() + 'PrintToBox', totalSize)
        } catch (BoxAPIException e) {
            println 'Error: Total size of the file set is too large'
            println boxErrorMessage(e)
        }
    }

    public void uploadFiles(files, BoxFolder uploadFolder, cmdLineOpts) {
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
            throw e
        } catch (e) {
            println 'Error: System could not upload the file to the target folder'
            println e.toString()
            throw e
        }
    }

    public void uploadFileToFolder(BoxFolder folder, InputStream fileStream, String fileName, long fileSize, String fileSHA1, cmdLineOpts) {

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

    private String boxErrorMessage(BoxAPIException boxAPIException) {
        String returnValue = boxAPIException.toString()
        def resp = boxAPIException.getResponse()

        if (resp != null) {
            returnValue += "\n" + JsonOutput.prettyPrint(resp)
        } else {
            returnValue += "\n" + boxAPIException.getLocalizedMessage()
        }

        return returnValue
    }

    public BoxFolder getFolder(BoxFolder folder, String folderName) {

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
    public void setupCollaboration(BoxFolder folder, BoxUser.Info myId, String user, String enterpriseDomain) {

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
            throw e
        } catch (e) {
            println 'Error: Could not set the collaboration on the target folder'
            println e.toString()
            throw e
        }
    }


}

