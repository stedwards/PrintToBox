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
            assert myOpts.enterpriseId instanceof String
            assert myOpts.keyId instanceof String
            assert myOpts.keyFileName instanceof String
            assert myOpts.keyPassword instanceof String

            assert (!myOpts.appUserId || myOpts.appUserId instanceof String)
            assert (!myOpts.baseFolderName || myOpts.baseFolderName instanceof String)

            this.putAll(myOpts.entrySet())

        } catch (AssertionError e) {
            println 'Error: Invalid config file: ' + """${ConfigFileName}
""" + 'Expected format (JSON):' + """
{
  "enterpriseDomain": "@example.com",
  "enterpriseId": "123456789",
  "clientId": "abcdefghijklmnopqrstuvwxyz123456",
  "clientSecret": "abcdefghijklmnopqrstuvwxyz123456",
  "keyId": "987654321",
  "keyFileName": "/etc/PrintToBox_private_key.pem",
  "keyPassword": "aoeu1234aoeu1234aoeu1234",
  "appUserId": "12349876"
}

Optional keys:
  "baseFolderName": "PrintToBox" (Default)"""

            throw e

        } catch (e) {
            println e.toString()
            println e.getCause().toString()
            throw e
        }
    }

}
