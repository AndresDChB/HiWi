package csvwriter; 

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.FileWriter;
import java.util.stream.*;
import static org.junit.Assert.assertTrue;

public final class CSVWriter {

    private CSVWriter(){}    

    public static String convertToCSV(String[] data) {
        return Stream.of(data)
          .map(CSVWriter::escapeSpecialCharacters)
          .collect(Collectors.joining(","));
    }

    public static void writeToCsv(String fileName, String newLine, boolean write) throws IOException {

        if (!write) {
            return;
        }
        // Expand tilde to the user's home directory if it exists
        if (fileName.startsWith("~")) {
            String home = System.getProperty("user.home");
            fileName = fileName.replaceFirst("^~", home);
        }
        File csvOutputFile = new File(fileName);
        // Use FileWriter with append set to true
        try (PrintWriter pw = new PrintWriter(new FileWriter(csvOutputFile, true))) {
            pw.println(newLine);
        }

        assert csvOutputFile.exists();
        System.out.println("CSV WRITER: Written new line to " + fileName);

    }

    public static String escapeSpecialCharacters(String data) {
        if (data == null) {
            throw new IllegalArgumentException("Input data cannot be null");
        }
        String escapedData = data.replaceAll("\\R", " ");
        if (data.contains(",") || data.contains("\"") || data.contains("'")) {
            data = data.replace("\"", "\"\"");
            escapedData = "\"" + data + "\"";
        }
        return escapedData;
    }
}
