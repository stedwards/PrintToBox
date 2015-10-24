import java.security.DigestInputStream
import java.security.MessageDigest

final class FilesHelper {

    FilesHelper() {}

    public long setFilesProperties(files, differ) {

        long totalSize = 0l

        try {
            files.each { fileName, fileProperties ->
                File file = new File((String)fileName)
                long fileSize = file.length()
                totalSize += fileSize
                FileInputStream fileStream = new FileInputStream(file)

                if (differ) {
                    MessageDigest sha = MessageDigest.getInstance("SHA1");
                    DigestInputStream digestInputStream = new DigestInputStream(fileStream, sha);
                    byte[] b = new byte[32768]
                    while (digestInputStream.read(b) != -1) ;
                    fileProperties.SHA1 = sprintf("%040x", new BigInteger(1, sha.digest()))
                    fileStream = new FileInputStream(file)
                }

                fileProperties.file = file
                fileProperties.size = fileSize
                fileProperties.stream = fileStream
            }

            return totalSize

        } catch (FileNotFoundException e) {
            println e.getMessage()
        }
    }
}
