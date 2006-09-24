package gsn.wrappers.ieee1451;

import com.thoughtworks.xstream.XStream;

import java.io.*;

public class TedsReader {

    public static String TARGET_DIR = "/Users/alisalehi/Desktop/TEDS/";


    public static void main(String[] args) {
        try {
            TEDS teds = readTedsFromXMLFile("micaONE.xml");
            System.out.println(teds.toHtmlString());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static TEDS readTedsFromXMLFile(String fileName) {
        XStream xs = new XStream();
        try {
            TEDS teds = new TEDS((Object[][][]) xs.fromXML(new FileInputStream(TARGET_DIR + fileName)));
            return teds;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static TEDS readTedsFromBinaryFile(String fileName) {
        FileInputStream fos;
        try {
            fos = new FileInputStream(new File(TARGET_DIR + fileName));
            ObjectInputStream os = new ObjectInputStream(fos);
            Object obj = os.readObject();
            os.close();
            return (new TEDS((Object[][][]) obj));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }
}
