package top.nkbe.npatch.patch;

import org.junit.Test;
import pxb.android.axml.AxmlParser;
import pxb.android.axml.AxmlWriter;
import pxb.android.axml.NodeVisitor;
import top.nkbe.npatch.patch.util.JavaLogger;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class ForkCompatibilityTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    @Test public void microgVendorHashAndC2dmSurviveUpstreamManifestChanges() throws Exception {
        for (String algorithm : new String[]{"sha1", "sha256"}) {
            NPatch patcher = patcher("--useMicroG", "--microgVendor", "test.vendor",
                    "--microgSignatureHash", algorithm, "-r", "--versioncode", "123456");
            byte[] output = rewrite(patcher);
            Map<String, Object> values = attributes(output);
            String digestAlgorithm = algorithm.equals("sha1") ? "SHA-1" : "SHA-256";
            byte[] digest = MessageDigest.getInstance(digestAlgorithm).digest(new byte[]{1, 2, 3});
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            assertEquals(hex.toString(), values.get("meta:test.vendor.android.gms.SPOOFED_PACKAGE_SIGNATURE"));
            assertEquals("test.original", values.get("meta:test.vendor.android.gms.SPOOFED_PACKAGE_NAME"));
            assertEquals("test.vendor.android.gms", values.get("meta:test.vendor.MICROG_PACKAGE_NAME"));
            assertEquals("010203", values.get("meta:fake-signature"));
            assertEquals("test.vendor.android.c2dm.intent.RECEIVE", values.get("action:name"));
            assertEquals("test.vendor.android.c2dm.permission.SEND", values.get("receiver:permission"));
            assertEquals(123456, values.get("manifest:versionCode"));
        }
    }

    @Test public void customKeystoreFormatsRemainSupported() throws Exception {
        Method method = NPatch.class.getDeclaredMethod("customKeystoreType", String.class);
        method.setAccessible(true);
        NPatch patcher = patcher();
        assertEquals("BKS", method.invoke(patcher, "custom.BKS"));
        assertEquals("PKCS12", method.invoke(patcher, "custom.p12"));
        assertEquals("PKCS12", method.invoke(patcher, "custom.PFX"));
        assertEquals("JKS", method.invoke(patcher, "custom.jks"));
        assertEquals("PKCS12", method.invoke(patcher("--keystoreType", "PKCS12"), "custom.bin"));
    }

    @Test public void oldSignaturesAreRemovedWithoutDeletingOtherMetaInfResources() throws Exception {
        Method method = NPatch.class.getDeclaredMethod("isApkSignatureEntry", String.class);
        method.setAccessible(true);
        for (String name : new String[]{"META-INF/MANIFEST.MF", "META-INF/CERT.RSA", "META-INF/CERT.SF", "META-INF/CERT.EC"}) {
            assertEquals(name, true, method.invoke(null, name));
        }
        for (String name : new String[]{"META-INF/services/example", "META-INF/OTHER.MF", "META-INF/foo.RSA/config", "assets/CERT.RSA"}) {
            assertEquals(name, false, method.invoke(null, name));
        }
    }

    @Test public void invalidCliOptionsFailBeforeProducingOutput() throws Exception {
        for (String[] args : new String[][]{{"-r", "--versioncode", "0"}, {"--microgSignatureHash", "md5"}, {"-l", "5"}, {"-l", "4"}}) {
            try {
                patcher(args).doCommandLine();
                fail("Invalid arguments must fail");
            } catch (NPatch.PatchError expected) {
                assertTrue(expected.getMessage().contains("Invalid patch arguments"));
            }
        }
    }

    private static NPatch patcher(String... args) {
        String[] all = java.util.Arrays.copyOf(args, args.length + 1);
        all[args.length] = "nonexistent.apk";
        return new NPatch(new JavaLogger(), all);
    }

    private static byte[] rewrite(NPatch patcher) throws Exception {
        AxmlWriter writer = new AxmlWriter();
        writer.ns("android", ANDROID, 1);
        NodeVisitor root = writer.child(null, "manifest");
        root.attr(null, "package", -1, NodeVisitor.TYPE_STRING, "test.original");
        NodeVisitor app = root.child(null, "application");
        NodeVisitor receiver = app.child(null, "receiver");
        receiver.attr(ANDROID, "permission", 0x01010006, NodeVisitor.TYPE_STRING, "com.google.android.c2dm.permission.SEND");
        NodeVisitor filter = receiver.child(null, "intent-filter");
        NodeVisitor action = filter.child(null, "action");
        action.attr(ANDROID, "name", 0x01010003, NodeVisitor.TYPE_STRING, "com.google.android.c2dm.intent.RECEIVE");
        action.end(); filter.end(); receiver.end(); app.end(); root.end(); writer.end();
        Method method = NPatch.class.getDeclaredMethod("modifyManifestFile", InputStream.class, String.class,
                int.class, String.class, String.class, String.class, boolean.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(patcher, new ByteArrayInputStream(writer.toByteArray()), "config", 28,
                "test.original", "test.renamed", "010203", false);
    }

    private static Map<String, Object> attributes(byte[] xml) throws Exception {
        Map<String, Object> values = new HashMap<>();
        AxmlParser parser = new AxmlParser(xml);
        int event;
        while ((event = parser.next()) != AxmlParser.END_FILE) {
            if (event != AxmlParser.START_TAG) continue;
            String metadataName = null;
            Object metadataValue = null;
            for (int i = 0; i < parser.getAttrCount(); i++) {
                String name = parser.getAttrName(i);
                Object value = parser.getAttrValue(i);
                values.put(parser.getName() + ":" + name, value);
                if (name.equals("name")) metadataName = (String) value;
                if (name.equals("value")) metadataValue = value;
            }
            if (parser.getName().equals("meta-data")) values.put("meta:" + metadataName, metadataValue);
        }
        return values;
    }
}
