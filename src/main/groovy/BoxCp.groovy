import com.box.sdk.BoxFolder
import com.box.sdk.BoxUser

final class BoxCp {
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
boxcp [<options>] <filename> [<filename 2>...] <[username]@example.com>:<folder/path...>

Upload files to a Box.com collaborated folder of which <username> is
the owner. Creates the collaborated folder and any subfolder[s] if they
do not exist. By default, it uploads a new version for existing files.

""", header: 'Options:')

        cli.d(longOpt:'differ', 'Upload new version only if the file differs')
        cli.D(longOpt:'debug', 'Enable debugging')
        cli.h(longOpt:'help', 'Print this help text')
        cli.R(longOpt:'replace', 'If the filename already exists in Box, delete it (and all versions) and replace it with this file')
        cli.T(longOpt:'total-size', 'Abort if total size of file set exceeds storage in Box. May not make sense with --replace, --differ, or --no-update')
        cli.U(longOpt:'no-update', 'If the filename already exists in Box, do nothing')
        cli.V(longOpt:'version', 'Display the program version and exit')

        cli.setExpandArgumentFiles(false)

        cmdLineOpts = cli.parse(args)

        if (cmdLineOpts.V) {
            println 'boxcp ' + VERSION
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

        try {
            configOpts = new ConfigHelper(CONFIG_FILE)
        } catch (e) {
            if (cmdLineOpts.D) e.printStackTrace()
            return
        }

        if (cmdLineOpts.arguments().size() < 2) {
            cli.usage()
            return
        }

        //FIXME: IN PrintToBox.groovy, createAppUser should allow and encourage you to create an email address because
        // the default is WHACK and users unlikely to be able to share with it

println cmdLineOpts.arguments()[-1].toString()

        //FIXME: Refactor
        if (cmdLineOpts.arguments()[-1].toString().contains((String) configOpts.enterpriseDomain + ':')) {
println 'Sending:'
            String target = cmdLineOpts.arguments()[-1]
            println 'target: ' + target
            int indexEnterpriseDomain = target.indexOf(((String) configOpts.enterpriseDomain) + ':')
            println 'idx: ' + indexEnterpriseDomain.toString()

            //FIXME: if no username before @enterprisedomain.com:folder then skip collaboration and upload to AppUser's account

            userName = target.substring(0, indexEnterpriseDomain)

            folderName = target.substring(indexEnterpriseDomain + configOpts.enterpriseDomain.toString().length() + 1)

            cmdLineOpts.arguments()[0..-2].each { k ->
                files[k] = [:]
                println 'file: ' + k
            }


        } else if (cmdLineOpts.arguments()[0].toString().contains((String) configOpts.enterpriseDomain + ':')) {
println 'Receiving:'
            String target = cmdLineOpts.arguments()[0]
            println 'target: ' + target
            int indexEnterpriseDomain = target.indexOf(((String) configOpts.enterpriseDomain) + ':')
            println 'idx: ' + indexEnterpriseDomain.toString()

            //FIXME don't need userName in this mode

            userName = target.substring(0, indexEnterpriseDomain)

            folderName = target.substring(indexEnterpriseDomain + configOpts.enterpriseDomain.toString().length() + 1)

            cmdLineOpts.arguments()[1..-1].each { k ->
                files[k] = [:]
                println 'file: ' + k
            }


        }

println userName
println folderName
return

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
