import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.channels.FileLock

final class TokensFileHelper {
    private FileLock tokensLock
    private RandomAccessFile tokensRAF
    private String TokensFileName
    private Integer Retries
    def tokens = [:]

    TokensFileHelper(String tokensFileName, Integer retries) {
        Retries = retries
        TokensFileName = tokensFileName
        openTokensFile()
    }

    def close() {
        //If the tokens file is inaccessible due to permissions, these are null
        if (tokensLock != null) tokensLock.release()
        if (tokensRAF != null) tokensRAF.close()
    }

    def getTokens() {
        if (tokens && tokens.containsKey('accessToken') && tokens.containsKey('refreshToken')) {
            return tokens
        }
        parseTokensFile()
        return tokens
    }

    def parseTokensFile() throws AssertionError {
        try {
            //Can't use an InputStream on the channel because it releases the lock and closes the file handle
            byte[] buf = new byte[tokensRAF.length().toInteger()]

            int bytes_read = tokensRAF.read(buf, 0, tokensRAF.length().toInteger())

            tokens = new JsonSlurper().parse(buf)

            assert tokens.accessToken instanceof String
            assert tokens.refreshToken instanceof String

        } catch (AssertionError e) {
            println 'Error: Invalid tokens file: ' + """${TokensFileName}
""" + 'Expected format (JSON):' + """
{
  "accessToken": "abcdefghijklmnopqrstuvwxyz123456",
  "refreshToken": "abcdefghijklmnopqrstuvwxyz123456"
}"""
            this.close()
            throw e
        }
    }

    def openTokensFile() throws Exception {
        try {
            tokensRAF = new RandomAccessFile(TokensFileName, "rw");

            //The program takes about 6 wall seconds to complete. Depending on the random sleep times chosen,
            //1000 loops gives the program between 16 and 100 minutes to complete, but probably ~ 1 hour.
            (1..Retries).find {
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
                println "Error: Cannot lock tokens file after ${Retries} tries. " +
                        'Consider setting the ("tokensLockRetries": 1234) option in the config file.'
                tokensRAF.close()
                throw new Exception('Failed to lock tokens file')
            }
        } catch (FileNotFoundException e) {
            // FileNotFound which is not an error
            tokens = [accessToken: null, refreshToken: null]
        }
    }

    def writeTokensToFile() {
        def jsonOutput = JsonOutput.toJson(tokens)
        byte[] jsonBytes = JsonOutput.prettyPrint(jsonOutput).getBytes()
        tokensRAF.seek(0)
        tokensRAF.write(jsonBytes, 0, jsonBytes.length)
    }

}
