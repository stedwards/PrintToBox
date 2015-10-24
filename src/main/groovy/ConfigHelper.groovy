import groovy.json.JsonParserType
import groovy.json.JsonSlurper

final class ConfigHelper implements Map {
    @Delegate Map inner = [:]

    private String ConfigFileName

    ConfigHelper(String configFile) {
        ConfigFileName = configFile
        readConfig()
    }

    public String getConfigFileName() {
        return ConfigFileName
    }

    private void readConfig() throws Exception {

        def myOpts = [:]

        try {
            //The LAX parser is the only one that supports comments (/* */) in JSON
            //However, it returns a horrible map type. Convert it here to a normal Groovy map.
            def slurpOpts = new JsonSlurper().setType(JsonParserType.LAX).parse(new File(ConfigFileName))

            slurpOpts.each {k, v -> myOpts.put(k, slurpOpts.get(k))}

            assert myOpts.clientId instanceof String
            assert myOpts.clientSecret instanceof String
            assert myOpts.enterpriseDomain instanceof String
            assert (!myOpts.tokensLockRetries || myOpts.tokensLockRetries instanceof Integer)
            assert (!myOpts.baseFolderName || myOpts.baseFolderName instanceof String)

            if (!myOpts.tokensLockRetries)
                myOpts.tokensLockRetries = 1000

            this.putAll(myOpts.entrySet())

        } catch (AssertionError e) {
            println 'Error: Invalid config file: ' + """${ConfigFileName}
""" + 'Expected format (JSON):' + """
{
  "enterpriseDomain": "@example.com",
  "clientId": "abcdefghijklmnopqrstuvwxyz123456",
  "clientSecret": "abcdefghijklmnopqrstuvwxyz123456"
}

Optional keys:
  "tokensLockRetries": 1000 (Default)
  "baseFolderName": "PrintToBox" (Default)"""

            throw e

        } catch (e) {
            println e.toString()
            println e.getCause().toString()
            throw e
        }
    }

}
