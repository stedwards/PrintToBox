# PrintToBox
PrintToBox is an application for uploading files for enterprise users to collaborated folders in 
[Box](https://www.box.com). E.g., cron output, logfiles, backups, etc.

Its initial inspiration was for [Banner](http://www.ellucian.com/student-information-system/) users to be able to 
print reports directly to Box in [INB](http://banner.wikia.com/wiki/Internet_Native_Banner). It should also work for 
Banner XE, provided the printing interface for reports has not changed (i.e., not [CUPS](https://cups.org/)).

## Usage
```
PrintToBox [<options>] <username> <filename> [<filename 2>...]

Upload files to a Box.com collaborated folder of which <username> is
the owner. Creates the collaborated folder and any subfolder[s] if they
do not exist. By default, it uploads a new version for existing files.

Options:
 -C,--create-user <username>  Create AppUser <username> and exit
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
 * N.b., **anyone in this group can read the config file, keys, and key password**. This is why it is a good idea to
 abstract access via CUPS or cron. Making the executable setuid=root is *not* recommended.
 
## Setting up configuration with Box.com
1. Generate a public/private keypair with a password on the private key. Recommended:

        openssl genrsa -aes256 -out /etc/PrintToBox/PrintToBox_private_key.pem 8192
        openssl rsa -pubout -in /etc/PrintToBox/PrintToBox_private_key.pem -out /etc/PrintToBox/PrintToBox_public_key.pem
        chmod 640 /etc/PrintToBox/PrintToBox_private_key.pem /etc/PrintToBox/PrintToBox_public_key.pem
        chown root:printtobox /etc/PrintToBox/PrintToBox_private_key.pem /etc/PrintToBox/PrintToBox_public_key.pem

2. Log into Box.com with an account with developer access
 * Sign up to be a developer here: https://developers.box.com
 * Then, go to your account settings (https://EXAMPLE.app.box.com/settings/security) and enable two-factor authentication (mandatory for below)
3. Create a new application
 * Go here: https://EXAMPLE.app.box.com/developers/services/edit/
 * Name it something unique. Select "Box Content" and press "Create Application"
 * For `redirect_uri`, set it to a bogus **https** URL
 * `User Type` is "App Users"
 * `Scopes` checked:
   * Read and write all files and folders
   * Create and manage app users
 * Click "Save Application"
 * Click "Add Public Key"
 * Copy the contents of `/etc/PrintToBox/PrintToBox_public_key.pem` into the "Public Key" field
 * Click "Verify" and then click "Save"
 * Click "Save Application"
 * Update `/etc/PrintToBox/PrintToBox.conf` with the following fields:
   * Enterprise domain (@example.com) &mdash; "enterpriseDomain"
   * Key Id &mdash; "keyId"
   * Private key filename &mdash; "keyFileName"
   * Private key password &mdash; "keyPassword"
   * client_id &mdash; "clientId"
   * client_secret &mdash; "clientSecret"
4. Go to the Admin Console
 * Go here: https://EXAMPLE.app.box.com/master/settings
 * Click "Business Settings". Click "Account Settings".
 * Update `/etc/PrintToBox/PrintToBox.conf` with the following fields:
   * `Enterprise ID` &mdash; "enterpriseId" 
 * Then, click the "App" tab and click "Authorize New App"
 * Copy the `client_id` from above into the "API Key" field and click "Okay"
5. Generate an AppUser and update `/etc/PrintToBox/PrintToBox.conf` with the "appUserId"

        $ PrintToBox -C <APP_USERNAME>
        Created user. Add this to /etc/PrintToBox/PrintToBox.conf:
        "appUserId": "9999999999" 

6. Try out a test upload

        PrintToBox <MY USERNAME> <TEST FILENAME>

7. Check the `PrintToBox <MY USERNAME>` folder for your uploaded file

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
