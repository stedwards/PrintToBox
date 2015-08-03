# PrintToBox
PrintToBox is an application for uploading files for enterprise users to collaborated folders in 
[Box](https://www.box.com).

Its initial inspiration was for Banner users to be able to print reports directly to Box in INB. It should also work
for Banner XE, provided the printing interface for reports has not changed (i.e., not CUPS).

It is based on standard enterprise user permissions. No admin/co-admin privileges are necessary.

It is recommended that you create a service account in Box with developer access rather than using a human's account. 

## Usage
```
PrintToBox [<options>] <username> <filename>

Upload <filename> to a Box.com collaborated folder of which <username> is
the owner. Creates the folder if it doesn't exist.

Options:
 -a <auth_code>   Auth code from OAUTH2 leg one
 -f <folder>      Box folder name. Should be unique per user. Default:
                  "PrintToBox <username>"
```

## Installation

### Building
1. Download the git repository
2. Run: `./gradlew uberjar`

### Installing the program
1. Review install.sh
2. `sudo ./install.sh`
3. `sudo useradd -a -G printtobox <username>`
 * N.b., **anyone in this group can read the config file and alter the tokens file**. This is why it is a good idea to
 abstract access via CUPS. Making the executable setuid=root is *not* recommended.
4. Edit/Review `/usr/lib/PrintToBox/PrintToBox.sh` for the proper `JAVA_HOME` path (Java 7+)
 
### Box
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
1. Add a printer. E.g., `sudo lpadmin -p 'BOXPRINTER' -E -v 'tea4cups://'`
2. Edit the Tea4CUPS config file, `/etc/cups/tea4cups.conf`, adding the following lines for the printer you are configuring:
```
[BOXPRINTER]
prehook_0 : /usr/bin/PrintToBox $TEAUSERNAME $TEADATAFILE >>/var/log/PrintToBox.log 2>>/var/log/PrintToBox.err ``` 
3. Test
```
lpr -PBOXPRINTER testfile
lpq -PBOXPRINTER
```

Unfortunately, the CUPS job filename is what Tea4CUPS receives so you are stuck with that. E.g., tea4cups-BOXPRINTER-username-216.

