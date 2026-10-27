import java.io.File;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.Provider;
import java.security.Security;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/**
 * External Android shell probe, NOT an app source set or an R8 input. Its only
 * application classpath is the exact signed release APK; no debug/test classes,
 * test-derived keeps, unoptimized dependencies, or replacement crypto provider.
 * Original entry names are resolved through that artifact's R8 mapping. Private
 * optimized entry points are intentional: tests must not alter release keeps.
 */
public final class ReleaseDexProbe {
    private static JSONObject map;
    private static File fixtures;
    private static int checks;

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void pass(String name) {
        checks++;
        System.out.println("PASS " + name);
    }

    private static Class<?> type(String original) throws Exception {
        if (original.endsWith("[]")) return Array.newInstance(type(original.substring(0, original.length()-2)), 0).getClass();
        if (original.equals("int")) return int.class;
        if (original.equals("boolean")) return boolean.class;
        if (original.equals("byte")) return byte.class;
        if (original.equals("float")) return float.class;
        return Class.forName(map.getJSONObject("classes").optString(original, original));
    }

    private static Object call(String owner, Object receiver, String signature, Object... args) throws Exception {
        String original = signature.substring(signature.indexOf(' ')+1);
        String parameters = original.substring(original.indexOf('(')+1, original.lastIndexOf(')'));
        String[] names = parameters.isEmpty() ? new String[0] : parameters.split(",");
        Class<?>[] types = new Class<?>[names.length];
        for (int i=0; i<names.length; i++) types[i] = type(names[i]);
        String renamed = map.getJSONObject("members").getJSONObject(owner).getString(signature);
        Method method = type(owner).getDeclaredMethod(renamed, types);
        method.setAccessible(true);
        return method.invoke(receiver, args);
    }

    private static Object field(String owner, Object receiver, String signature) throws Exception {
        String renamed = map.getJSONObject("members").getJSONObject(owner).getString(signature);
        Field field = type(owner).getDeclaredField(renamed);
        field.setAccessible(true);
        return field.get(receiver);
    }

    private static void constantsAndCommands() throws Exception {
        JSONObject constants = map.getJSONObject("constants");
        Class<?> codes = type("com.stripe1.xmouse.InputEventCodes");
        Iterator<String> iterator = constants.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            require(codes.getDeclaredField(key).getInt(null) == constants.getInt(key), key);
        }
        pass("all " + constants.length() + " dynamic KEY_* field names/values including aliases");
        List<String> commands = new ArrayList<>();
        Class<?> transport = type("com.stripe1.xmouse.CommandTransport");
        Object recording = Proxy.newProxyInstance(transport.getClassLoader(), new Class<?>[]{transport},
            (proxy, method, args) -> { commands.add((String)args[0]); return true; });
        Constructor<?> constructor = type("com.stripe1.xmouse.YDoTool").getDeclaredConstructor(transport);
        constructor.setAccessible(true);
        Object tool = constructor.newInstance(recording);
        String[][] cases = {
            {"XF86AudioRaiseVolume", "115:1 115:0"}, {"Alt+F4", "56:1 62:1 62:0 56:0"},
            {"Ctrl+plus", "29:1 78:1 78:0 29:0"}, {"ctrl+t", "29:1 20:1 20:0 29:0"},
            {"Ctrl+alt+t", "29:1 56:1 20:1 20:0 56:0 29:0"},
            {"Ctrl+Alt+F1", "29:1 56:1 59:1 59:0 56:0 29:0"},
            {"XF86MonBrightnessDown", "224:1 224:0"}, {"MACRO30", "685:1 685:0"},
            {"NOT_REAL_unknown", "0:1 0:0"}
        };
        for (String[] item : cases) {
            commands.clear();
            call("com.stripe1.xmouse.YDoTool", tool, "void key(java.lang.String)", item[0]);
            require(commands.equals(Collections.singletonList("ydotool key " + item[1])), "command: " + item[0]);
            pass("optimized YDoTool reflection: " + item[0]);
        }
    }

    private static void crypto() throws Exception {
        List<String> before = new ArrayList<>();
        for (Provider provider : Security.getProviders()) before.add(provider.getName()+":"+provider.getClass().getName());
        org.json.JSONArray algorithms = map.getJSONArray("algorithms");
        Set<String> javaOnly = new HashSet<>(Arrays.asList("KeyPairGenEdDSA", "SignatureEd25519", "SignatureEd448", "XDH", "MLKEM768", "MLKEM1024"));
        int unavailable = 0;
        for (int i=0; i<algorithms.length(); i++) {
            String name = algorithms.getString(i);
            System.out.println("CONSTRUCT " + name);
            Constructor<?> implementation = Class.forName(name).getDeclaredConstructor();
            implementation.setAccessible(true); // JSch itself lives in the package of its auth classes.
            try { require(implementation.newInstance() != null, name); }
            catch (InvocationTargetException failure) {
                // These six Java-8 base classes deliberately throw; their real
                // implementations live in JAR multi-release Java 11/15/24 entries,
                // not Android. JSch selects the shipped BC counterparts instead.
                String simple = name.substring(name.lastIndexOf('.')+1);
                int version = simple.startsWith("MLKEM") ? 24 : simple.equals("XDH") ? 11 : 15;
                boolean expectedStub = name.startsWith("com.jcraft.jsch.jce.") && javaOnly.contains(simple) &&
                    failure.getCause() instanceof UnsupportedOperationException &&
                    ((simple.startsWith("MLKEM") ? "MLKEM" : simple.startsWith("SignatureEd") ? "SignatureEdDSA" : simple)+
                        " requires Java"+version+"+.").equals(failure.getCause().getMessage());
                if (!expectedStub) throw new AssertionError("unexpected constructor failure: " + name, failure);
                unavailable++;
            }
        }
        require(unavailable == 6, "exactly six deliberately unsupported Java-only stubs");
        pass("all " + algorithms.length() + " JSch constructors resolved: 110 constructed, 6 exact Java-only stub rejections");
        Map<?,?> config = (Map<?,?>)field("com.jcraft.jsch.JSch", null, "java.util.Hashtable config");
        Map<?,?> configBefore = new HashMap<>(config);
        for (String name : new String[]{"xdh", "keypairgen.eddsa", "ssh-ed25519", "ssh-ed448"}) {
            require(((String)config.get(name)).startsWith("com.jcraft.jsch.bc."), "Android BC routing: " + name);
        }
        pass("JSch selects BC rather than unsupported Java-only EdDSA/XDH stubs");
        Constructor<?> constructor = type("org.bouncycastle.jce.provider.BouncyCastleProvider").getDeclaredConstructor();
        constructor.setAccessible(true);
        Provider provider = (Provider)constructor.newInstance();
        byte[] text = "NOT_REAL optimized provider fixture".getBytes(StandardCharsets.US_ASCII);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS7Padding", provider);
        SecretKeySpec key = new SecretKeySpec(new byte[32], "AES");
        IvParameterSpec iv = new IvParameterSpec(new byte[16]);
        cipher.init(Cipher.ENCRYPT_MODE, key, iv);
        byte[] encrypted = cipher.doFinal(text);
        cipher.init(Cipher.DECRYPT_MODE, key, iv);
        require(Arrays.equals(text, cipher.doFinal(encrypted)), "BC AES CBC roundtrip");
        pass("actual release BC $Mappings -> AES JCA SPI encrypt/decrypt");
        String owner = "com.stripe1.xmouse.secrets.BoundedSshKey";
        for (String name : new String[]{"rsa1024.pem", "rsa1024-encrypted.pem", "ecdsa-p256.pem",
                "ecdsa-p256-encrypted.pem", "rsa-pkcs8-encrypted.pem", "ed25519.pem", "ed448.pem"}) {
            byte[] phrase = name.contains("encrypted") ? "NOT_REAL_XMOUSE_FIXTURE".getBytes(StandardCharsets.US_ASCII) : new byte[0];
            byte[] bytes = Files.readAllBytes(new File(fixtures, name).toPath());
            call(owner, null, "void validate$app(byte[],byte[])", bytes, phrase);
            pass("bounded normalize + real JSch sign/verify: " + name);
            if (name.contains("encrypted")) {
                for (byte[] wrong : new byte[][]{new byte[0], "NOT_REAL_wrong".getBytes(StandardCharsets.US_ASCII)}) {
                    boolean rejected = false;
                    try { call(owner, null, "void validate$app(byte[],byte[])", bytes, wrong); }
                    catch (InvocationTargetException expected) {
                        rejected = type("com.stripe1.xmouse.secrets.PrivateKeyImportException").isInstance(expected.getCause());
                    }
                    require(rejected, "wrong/missing passphrase must fail: " + name);
                    pass("bounded encrypted fixture rejects " + (wrong.length == 0 ? "missing" : "wrong") + " phrase: " + name);
                }
            }
        }
        boolean rejected = false;
        try { call(owner, null, "void validate$app(byte[],byte[])", new byte[]{1,2,3}, new byte[0]); }
        catch (InvocationTargetException expected) {
            rejected = type("com.stripe1.xmouse.secrets.PrivateKeyImportException").isInstance(expected.getCause());
        }
        require(rejected, "malformed key must not be accepted");
        pass("bounded malformed key rejection");
        List<String> after = new ArrayList<>();
        for (Provider current : Security.getProviders()) after.add(current.getName()+":"+current.getClass().getName());
        require(before.equals(after), "no global security provider registration");
        require(configBefore.equals(config), "no global JSch config change");
        pass("global crypto providers unchanged");
    }

    private static void xmlAndMigrationCodec() throws Exception {
        String owner = "com.stripe1.xmouse.layout.LayoutProfileXml";
        String signature = "com.stripe1.xmouse.layout.LayoutProfileXml$ParsedLayout parseDocument(java.lang.String,java.lang.String)";
        String source = "<?xml version=\"1.0\"?><layout version=\"1\" id=\"probe\" name=\"NOT_REAL &amp; Layout\" columns=\"10\">"+
            "<button id=\"button\" width=\"4\" color=\"#DDEEFF\"><title>One &amp; two</title>"+
            "<command>xdotool key ctrl+alt+t</command></button></layout>";
        Object parsed = call(owner, null, signature, source, "probe");
        Object profile = field(owner+"$ParsedLayout", parsed, "com.stripe1.xmouse.layout.LayoutProfile profile");
        String formatted = (String)call(owner, null,
            "java.lang.String serialize(com.stripe1.xmouse.layout.LayoutProfile,java.util.List)", profile, Collections.emptyList());
        Object roundtrip = call(owner, null, signature, formatted, "probe");
        require(profile.equals(field(owner+"$ParsedLayout", roundtrip, "com.stripe1.xmouse.layout.LayoutProfile profile")), "XML roundtrip");
        pass("optimized XML SAX parse/serialize preserves profile identity/content");
        for (String bad : new String[]{source.replace("id=\"probe\"", "id=\"other\""),
                "<!DOCTYPE layout [<!ENTITY x SYSTEM \"file:///NOT_REAL_never_read\">]>"+source,
                source.replace("<title>", "<unknown>"), source.replace("width=\"4\"", "width=\"11\"")}) {
            boolean rejected = false;
            try { call(owner, null, signature, bad, "probe"); }
            catch (InvocationTargetException expected) {
                rejected = type("com.stripe1.xmouse.layout.LayoutXmlException").isInstance(expected.getCause());
            }
            require(rejected, "XML invalid identity/DTD/schema/width must fail");
            pass("optimized XML invalid identity/DTD/schema/width rejection");
        }
        String migrator = "com.stripe1.xmouse.layout.LegacyLayoutProfileMigrator";
        List<?> sources = (List<?>)field(migrator, null, "java.util.List SOURCES");
        // Use a real migration fixture if available; readButtons is the production
        // migration parser after R8 inlining, not a reimplementation in this probe.
        byte[] bytes = Files.readAllBytes(new File(fixtures, "keyLayoutFile.csv").toPath());
        List<?> buttons = (List<?>)call(migrator, null,
            "java.util.List readButtons(byte[],com.stripe1.xmouse.layout.LegacyLayoutProfileMigrator$LegacyLayoutSource)", bytes, sources.get(0));
        require(buttons.size() == 2, "legacy parser fixture count");
        pass("optimized legacy migration reader parses retained CSV fixture");
    }

    private static void dnsCodec() throws Exception {
        String owner = "com.stripe1.xmouse.transport.MdnsAddressCodec";
        Object name = call(owner, null, "org.xbill.DNS.Name localName(java.lang.String)", "not-real-fixture.local");
        require(name != null, "local DNS name");
        for (int type : new int[]{1,28}) {
            byte[] address = type == 1 ? new byte[]{(byte)192,(byte)168,7,9} :
                new byte[]{(byte)0xfd,0x12,0x34,0x56,0,0,0,0,0,0,0,0,0,0,0,1};
            byte[] query = (byte[])call(owner, null, "byte[] query(org.xbill.DNS.Name,int,int)", name, type, 1234);
            byte[] response = ByteBuffer.allocate(query.length + 12 + address.length).put(query)
                .putShort((short)0xc00c).putShort((short)type).putShort((short)1).putInt(120)
                .putShort((short)address.length).put(address).array();
            response[2] = (byte)0x84;
            response[7] = 1;
            String signature = "java.util.List decode(byte[],org.xbill.DNS.Name,int,int)";
            List<?> decoded = (List<?>)call(owner, null, signature, response, name, type, 1234);
            require(decoded.size()==1 && Arrays.equals(address, ((java.net.InetAddress)decoded.get(0)).getAddress()), "A/AAAA codec");
            pass("optimized dnsjava codec with compressed " + (type==1 ? "A" : "AAAA") + " answer");
            boolean rejected = false;
            try { call(owner, null, signature, Arrays.copyOf(response, response.length+1), name, type, 1234); }
            catch (InvocationTargetException expected) { rejected = expected.getCause() instanceof IllegalArgumentException; }
            require(rejected, "malformed DNS tail rejected");
            pass("optimized DNS rejects malformed " + (type==1 ? "A" : "AAAA") + " tail");
        }
    }

    private static void run(String[] args) throws Exception {
        require(args.length == 2, "runtime-map.json and synthetic fixture directory required");
        map = new JSONObject(new String(Files.readAllBytes(new File(args[0]).toPath()), StandardCharsets.UTF_8));
        fixtures = new File(args[1]);
        constantsAndCommands();
        crypto();
        xmlAndMigrationCodec();
        dnsCodec();
        System.out.println("RELEASE_DEX_PROBE_OK checks=" + checks + " api=" + android.os.Build.VERSION.SDK_INT);
    }

    public static void main(String[] args) {
        try { run(args); }
        catch (Throwable failure) {
            // app_process's default uncaught handler writes only to logcat then
            // SIGKILLs itself. Retain useful synthetic-fixture failures instead.
            failure.printStackTrace(System.out);
            System.exit(1);
        }
    }
}
