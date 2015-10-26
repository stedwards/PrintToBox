import com.box.sdk.BoxFolder
import com.box.sdk.BoxUser
import java.util.logging.Level
import java.util.logging.Logger

final class PrintToBox {
    private static final String VERSION = '2.0'
    private static final String CONFIG_FILE = '/etc/PrintToBox.conf'
    private static final String TOKENS_FILE = '/var/cache/PrintToBox/tokens'

    static void main(String[] args) {
        BoxUser.Info userInfo
        BoxFolder rootFolder
        BoxFolder collaborationFolder
        BoxFolder printFolder
        long totalSize
        def cli
        def cmdLineOpts
        def configOpts
        def files = [:]
        def tokens
        def tokensFile
        String folderName
        String userName
        String AUTH_CODE = ''

        cli = new CliBuilder(usage: """
PrintToBox [<options>] <username> <filename> [<filename 2>...]

Upload files to a Box.com collaborated folder of which <username> is
the owner. Creates the collaborated folder and any subfolder[s] if they
do not exist. By default, it uploads a new version for existing files.

""", header: 'Options:')

        cli.a(longOpt:'auth-code', args: 1, argName:'auth_code', 'Auth code from OAUTH2 leg one')
        cli.C(longOpt:'create-user', args: 1, argName:'create_user', 'Create AppAuth <username> and exit')
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

        if (cmdLineOpts.h) {
            cli.usage()
            return
        }

        if (cmdLineOpts.R && cmdLineOpts.U) {
            println 'Error: -R/--replace and -U/--no-update are mutually exclusive options. See --help for details.'
            return
        }

        try {
            configOpts = new ConfigHelper(CONFIG_FILE)
        } catch (e) {
            if (cmdLineOpts.D) e.printStackTrace()
            return
        }

        if (cmdLineOpts.C) {
            try {
                BoxHelper boxHelper = new BoxHelper()
                String userId = boxHelper.createAppUser(configOpts, (String) cmdLineOpts.C)
                println """Created user. Add this to ${CONFIG_FILE}:
"appUserId": "${userId}" """
            } catch (e) {
                if (cmdLineOpts.D) e.printStackTrace()
            }
            return
        }

        if (cmdLineOpts.arguments().size() < 2) {
            cli.usage()
            return
        }

        userName = cmdLineOpts.arguments()[0]

        cmdLineOpts.arguments()[1..-1].each { k ->
            files[k] = [:]
        }

        if (cmdLineOpts.a)
            AUTH_CODE = cmdLineOpts.a

        if (cmdLineOpts.f) {
            folderName = cmdLineOpts.f
        } else if (configOpts.baseFolderName) {
            folderName = configOpts.baseFolderName + ' ' + userName
        } else {
            folderName = 'PrintToBox ' + userName
        }

/*        try {
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
*/
        try {
            totalSize = new FilesHelper().setFilesProperties(files, cmdLineOpts."differ")

            BoxHelper boxHelper = new BoxHelper(configOpts)
            // BoxHelper boxHelper = new BoxHelper(configOpts, AUTH_CODE, tokens)

            // boxHelper.updateTokens(tokens)
            // tokensFile.writeTokensToFile(tokens)

            userInfo            = boxHelper.getAPIUserInfo()
            rootFolder          = boxHelper.getRootFolder()
            collaborationFolder = boxHelper.getCollaborationFolder(rootFolder, folderName)

            boxHelper.setupCollaboration(collaborationFolder, userInfo, userName, (String) configOpts.enterpriseDomain)

            printFolder = boxHelper.getUploadFolder(collaborationFolder, folderName)

            if (cmdLineOpts."total-size" && files.size() > 1) {
                boxHelper.checkUploadSize(printFolder, totalSize)
            }

            boxHelper.uploadFiles(files, printFolder, cmdLineOpts)

        } catch (e) {
            if (cmdLineOpts.D) e.printStackTrace()
        } /* finally {
            tokensFile.close()
        } */
    } //end main()
}
