# PrintToBox
PrintToBox is an application for uploading files for enterprise users to collaborated folders in 
[Box](https://www.box.com). E.g., cron output, logfiles, backups, etc.

Its initial inspiration was for [Banner](http://www.ellucian.com/student-information-system/) users to be able to 
print reports directly to Box in [INB](http://banner.wikia.com/wiki/Internet_Native_Banner). It should also work for 
Banner XE, provided the printing interface for reports has not changed (i.e., not [CUPS](https://cups.org/)).

It is based on standard enterprise user permissions. No admin/co-admin privileges are necessary.

It is recommended that you create a service account in Box with developer access rather than using a human's account. 

## Usage
```
PrintToBox [<options>] <username> <filename> [<filename 2>...]

Upload files to a Box.com collaborated folder of which <username> is
the owner. Creates the collaborated folder and any subfolder[s] if they
do not exist. By default, it uploads a new version for existing files.

Options:
 -a,--auth-code <auth_code>   Auth code from OAUTH2 leg one
 -d,--differ                  Upload new version only if the file differs
 -f,--folder <folder>         Box folder path. Top-level should be unique.
                              Default: "PrintToBox <username>"
 -h,--help                    Print this help text
 -R,--replace                 If the filename already exists in Box,
                              delete it (and all versions) and replace it
                              with this file
 -T,--total-size              Abort if total size of file set exceeds
                              storage in Box. May not make sense with
                              --replace, --differ, or --no-update
 -U,--no-update               If the filename already exists in Box, do
                              nothing
 -V,--version                 Display the program version and exit
```

## Building and Installing
1. Install Java 7+ and ensure `/usr/bin/java -version` or `JAVA_HOME` is set to it
2. Download the git repository
3. Build a package
   * RedHat/RPM distros: `./gradlew createRpm`
   * Ubuntu/DEB distros: `./gradlew createDeb`
4. Install the package
   * RedHat/RPM distros: `sudo rpm -i build/distributions/printtobox-VERSION.rpm`
   * Ubuntu/DEB distros: `sudo dpkg -i build/distributions/printtobox_VERSION.deb`
5. `sudo usermod -a -G printtobox <username>`
 * N.b., **anyone in this group can read the config file and alter the tokens file**. This is why it is a good idea to
 abstract access via CUPS. Making the executable setuid=root is *not* recommended.
 
## Setting up configuration with Box.com
1. Create a service account with developer access
2. Log into the service account in Box.com
3. Create a new application
 * The only checkbox under `Scopes` it needs is "Read and write all files and folders"
 * For `redirect_uri`, set it to a bogus URL
4. Copy `client_id` and `client_secret` into the appropriate variables in `/etc/PrintToBox.conf`
5. Edit this URL, setting `CLIENT_ID` appropriately, and go to it in a Web browser 
`https://app.box.com/api/oauth2/authorize?response_type=code&client_id=CLIENT_ID`
6. You'll be redirected to your bogus URL. Copy the value for the `code` parameter in the URL bar
7. You have about 10 seconds to execute this:
```
/usr/bin/PrintToBox -a <code> <username> <filename>
```
 * Assuming permissions and everything are correct, it will generate `/var/cache/PrintToBox/tokens` and upload the file
 to the supplied user
 * If you're too slow, do steps 5-7 again
 
## Tea4CUPS
1. Install Tea4CUPS using your distribution package manager or get it here: [Tea4CUPS](http://www.pykota.com/software/tea4cups)
2. Add a printer. E.g., `sudo lpadmin -p 'BOXPRINTER' -E -v 'tea4cups://'`
3. Edit the Tea4CUPS config file, `/etc/cups/tea4cups.conf`, adding the following lines for the printer you are configuring:
        
        [BOXPRINTER]
        prehook_0 : /usr/bin/PrintToBox $TEAUSERNAME $TEADATAFILE >>/var/log/PrintToBox.log 2>>/var/log/PrintToBox.err
        
4. Test
        
        lpr -PBOXPRINTER testfile
        lpq -PBOXPRINTER

Unfortunately, the CUPS job filename is what Tea4CUPS receives so you are stuck with that. E.g., `tea4cups-BOXPRINTER-username-216`.
