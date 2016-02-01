import com.box.sdk.BoxFolder
import com.box.sdk.BoxUser
import org.codehaus.groovy.GroovyException

final class PrintToBox {
    private static final String VERSION = '2.0'
    private static final String CONFIG_FILE = '/etc/PrintToBox/PrintToBox.conf'

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
        String folderName
        String userName

        cli = new CliBuilder(usage: """
PrintToBox [<options>] <username> <filename> [<filename 2>...]

Upload files to a Box.com collaborated folder of which <username> is
the owner. Creates the collaborated folder and any subfolder[s] if they
do not exist. By default, it uploads a new version for existing files.

""", header: 'Options:')

        cli.C(longOpt:'create-user', args: 1, argName:'username', 'Create AppUser <username> and exit')
        cli.d(longOpt:'differ', 'Upload new version only if the file differs')
        cli.D(longOpt:'debug', 'Enable debugging')
        cli._(longOpt:'delete-user', 'CAUTION: Delete configured AppUser and exit')
        cli.f(longOpt:'folder', args: 1, argName:'folder', 'Box folder path. Top-level should be unique. Default: "PrintToBox <username>"')
        cli.h(longOpt:'help', 'Print this help text and exit')
        cli.I(longOpt:'user-info', 'Print information about the configured AppUser and exit')
        cli.R(longOpt:'replace', 'If the filename already exists in Box, delete it (and all versions) and replace it with this file')
        cli.T(longOpt:'total-size', 'Abort if total size of file set exceeds storage in Box. May not make sense with --replace, --differ, or --no-update')
        cli.U(longOpt:'no-update', 'If the filename already exists in Box, do nothing')
        cli.V(longOpt:'version', 'Display the program version and exit')

        try {
            cmdLineOpts = cli.parse(args)
            if (!cmdLineOpts)
                throw new GroovyException('Error with commandline options')
        } catch (e) {
            System.exit(1)
        }

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
            System.exit(1)
        }

        if (cmdLineOpts.C && cmdLineOpts.I || cmdLineOpts.C && cmdLineOpts.E || cmdLineOpts.E && cmdLineOpts.I) {
            println 'Error: -C/--create-user, --delete-user, and -I/--user-info are mutually exclusive options. See --help for details.'
            System.exit(1)
        }

        try {
            configOpts = new ConfigHelper(CONFIG_FILE)
        } catch (e) {
            if (cmdLineOpts.D) e.printStackTrace()
            return
        }

        if (cmdLineOpts.I) {
            try {
                BoxHelper boxHelper = new BoxHelper(configOpts)
                def userProperties = boxHelper.getAppUserProperties()
                println 'Login: ' + userProperties.login
                println 'ID: ' + userProperties.id
                println 'Name: ' + userProperties.name
                return
            } catch (e) {
                if (cmdLineOpts.D) e.printStackTrace()
                System.exit(1)
            }
        }

        if (cmdLineOpts.C) {
            try {
                BoxHelper boxHelper = new BoxHelper()
                String userId = boxHelper.createAppUser(configOpts, (String) cmdLineOpts.C)
                println """Created user. Add this to ${CONFIG_FILE}:
"appUserId": "${userId}" """
                return
            } catch (e) {
                if (cmdLineOpts.D) e.printStackTrace()
                System.exit(1)
            }
        }

        if (cmdLineOpts.'delete-user') {
            try {
                BoxHelper boxHelper = new BoxHelper()
                boxHelper.deleteAppUser(configOpts)
                println """Deleted AppUser. Update this line in ${CONFIG_FILE}:
"appUserId": "${configOpts.appUserId}" """
                return
            } catch (e) {
                if (cmdLineOpts.D) e.printStackTrace()
                System.exit(1)
            }
        }

        if (cmdLineOpts.arguments().size() < 2) {
            cli.usage()
            return
        }

        userName = cmdLineOpts.arguments()[0]

        cmdLineOpts.arguments()[1..-1].each { k ->
            files[k] = [:]
        }

        if (cmdLineOpts.f) {
            folderName = cmdLineOpts.f
        } else if (configOpts.baseFolderName) {
            folderName = configOpts.baseFolderName + ' ' + userName
        } else {
            folderName = 'PrintToBox ' + userName
        }

        try {
            totalSize = new FilesHelper().setFilesProperties(files, cmdLineOpts."differ")

            BoxHelper boxHelper = new BoxHelper(configOpts)
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
            System.exit(1)
        }
    } //end main()
}
