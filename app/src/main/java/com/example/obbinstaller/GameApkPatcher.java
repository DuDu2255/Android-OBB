package com.example.obbinstaller;

import android.content.Context;
import android.security.KeyPairGeneratorSpec;
import com.android.apksig.ApkSigner;
import com.android.zipflinger.BytesSource;
import com.android.zipflinger.Entry;
import com.android.zipflinger.ZipArchive;
import com.android.zipflinger.ZipSource;
import java.util.zip.Deflater;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.security.auth.x500.X500Principal;
import javax.xml.parsers.DocumentBuilderFactory;
import net.dongliu.apk.parser.ApkFile;
import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.builder.Label;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21t;
import org.jf.dexlib2.builder.instruction.BuilderInstruction3rc;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.ImmutableMethodParameter;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import java.io.StringReader;

final class GameApkPatcher {
    interface Logger {
        void log(String message);
    }

    static final class Result {
        final File apk;
        final String packageName;
        final String launcherActivity;

        Result(File apk, String packageName, String launcherActivity) {
            this.apk = apk;
            this.packageName = packageName;
            this.launcherActivity = launcherActivity;
        }
    }

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String PAYLOAD_CLASS = "Lcom/example/obbpayload/ObbPayload;";
    private static final String KEY_ALIAS = "obb_game_patch_key";
    private final Context context;
    private final Logger logger;

    GameApkPatcher(Context context, Logger logger) {
        this.context = context.getApplicationContext();
        this.logger = logger;
    }

    Result patch(File inputApk) throws Exception {
        logger.log("开始解析 APK：" + inputApk + "，大小=" + inputApk.length());
        String manifestXml;
        String packageName;
        try (ApkFile apkFile = new ApkFile(inputApk)) {
            manifestXml = apkFile.getManifestXml();
            packageName = apkFile.getApkMeta().getPackageName();
        }
        String launcher = findLauncherActivity(manifestXml, packageName);
        logger.log("APK 包名=" + packageName + "，启动 Activity=" + launcher);

        PatchedDex patched = patchLauncherDex(inputApk, launcher);
        byte[] payloadDex = readAll(context.getAssets().open("classes.dex"));
        File unsigned = new File(context.getCacheDir(), "game-patched-unsigned.apk");
        File signed = new File(context.getCacheDir(), "game-patched.apk");
        buildUnsignedApk(inputApk, unsigned, patched.entryName, patched.data, payloadDex);
        signApk(unsigned, signed);
        if (!unsigned.delete()) logger.log("未能删除临时 unsigned APK");
        logger.log("修补完成：" + signed + "，大小=" + signed.length());
        return new Result(signed, packageName, launcher);
    }

    private String findLauncherActivity(String xml, String packageName) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(
                new InputSource(new StringReader(xml)));
        NodeList activities = document.getElementsByTagName("activity");
        for (int i = 0; i < activities.getLength(); i++) {
            Element activity = (Element) activities.item(i);
            if (isLauncher(activity)) return resolveClassName(packageName, androidAttr(activity, "name"));
        }
        NodeList aliases = document.getElementsByTagName("activity-alias");
        for (int i = 0; i < aliases.getLength(); i++) {
            Element alias = (Element) aliases.item(i);
            if (isLauncher(alias)) {
                return resolveClassName(packageName, androidAttr(alias, "targetActivity"));
            }
        }
        throw new IOException("APK 清单中未找到 MAIN/LAUNCHER Activity");
    }

    private boolean isLauncher(Element component) {
        NodeList filters = component.getElementsByTagName("intent-filter");
        for (int i = 0; i < filters.getLength(); i++) {
            Element filter = (Element) filters.item(i);
            boolean main = false;
            boolean launcher = false;
            NodeList children = filter.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (!(child instanceof Element)) continue;
                Element element = (Element) child;
                String name = androidAttr(element, "name");
                if ("action".equals(element.getTagName())
                        && "android.intent.action.MAIN".equals(name)) main = true;
                if ("category".equals(element.getTagName())
                        && "android.intent.category.LAUNCHER".equals(name)) launcher = true;
            }
            if (main && launcher) return true;
        }
        return false;
    }

    private String androidAttr(Element element, String name) {
        String value = element.getAttributeNS(ANDROID_NS, name);
        return value.isEmpty() ? element.getAttribute("android:" + name) : value;
    }

    private String resolveClassName(String packageName, String name) throws IOException {
        if (name == null || name.isEmpty()) throw new IOException("Activity 类名为空");
        if (name.startsWith(".")) return packageName + name;
        if (name.indexOf('.') < 0) return packageName + "." + name;
        return name;
    }

    private PatchedDex patchLauncherDex(File apk, String launcher) throws Exception {
        String targetType = "L" + launcher.replace('.', '/') + ";";
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(\\d*)?\\.dex")) continue;
                byte[] dexBytes = readAll(zip.getInputStream(entry));
                DexBackedDexFile dex = new DexBackedDexFile(Opcodes.getDefault(), dexBytes);
                boolean found = false;
                for (ClassDef classDef : dex.getClasses()) {
                    if (targetType.equals(classDef.getType())) {
                        found = true;
                        break;
                    }
                }
                if (!found) continue;
                logger.log("启动类位于 " + entry.getName());
                return new PatchedDex(entry.getName(), rewriteDex(dex, targetType));
            }
        }
        throw new IOException("在 classes*.dex 中找不到启动类；APK 可能已加固");
    }

    private byte[] rewriteDex(DexBackedDexFile dex, String targetType) throws Exception {
        List<ClassDef> classes = new ArrayList<>();
        boolean patched = false;
        for (ClassDef classDef : dex.getClasses()) {
            if (!targetType.equals(classDef.getType())) {
                classes.add(classDef);
                continue;
            }
            List<Method> methods = new ArrayList<>();
            boolean hasOnCreate = false;
            for (Method method : classDef.getMethods()) {
                if (isOnCreate(method)) {
                    hasOnCreate = true;
                    methods.add(patchOnCreate(method));
                } else {
                    methods.add(method);
                }
            }
            if (!hasOnCreate) methods.add(createOnCreate(classDef));
            classes.add(new ImmutableClassDef(
                    classDef.getType(),
                    classDef.getAccessFlags(),
                    classDef.getSuperclass(),
                    classDef.getInterfaces(),
                    classDef.getSourceFile(),
                    classDef.getAnnotations(),
                    classDef.getFields(),
                    methods));
            patched = true;
        }
        if (!patched) throw new IOException("无法修改启动 Activity");
        DexFile outputDex = new ImmutableDexFile(dex.getOpcodes(), classes);
        MemoryDataStore store = new MemoryDataStore();
        DexPool.writeTo(store, outputDex);
        return store.getData();
    }

    private boolean isOnCreate(Method method) {
        return "onCreate".equals(method.getName())
                && "V".equals(method.getReturnType())
                && method.getParameterTypes().size() == 1
                && "Landroid/os/Bundle;".contentEquals(method.getParameterTypes().get(0));
    }

    private Method patchOnCreate(Method method) throws IOException {
        MethodImplementation implementation = method.getImplementation();
        if (implementation == null) throw new IOException("启动 Activity 的 onCreate 没有代码");
        int parameterRegisters = 2;
        int localCount = implementation.getRegisterCount() - parameterRegisters;
        if (localCount < 1) {
            throw new IOException("启动 Activity 的 onCreate 没有可用局部寄存器");
        }
        int thisRegister = implementation.getRegisterCount() - parameterRegisters;
        MutableMethodImplementation mutable = new MutableMethodImplementation(implementation);
        ImmutableMethodReference handler = payloadHandlerReference();
        mutable.addInstruction(0, new BuilderInstruction3rc(
                Opcode.INVOKE_STATIC_RANGE, thisRegister, 1, handler));
        mutable.addInstruction(1, new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
        mutable.addInstruction(2, new BuilderInstruction10x(Opcode.NOP));
        mutable.addInstruction(3, new BuilderInstruction10x(Opcode.RETURN_VOID));
        Label original = mutable.newLabelForIndex(4);
        mutable.replaceInstruction(2, new BuilderInstruction21t(Opcode.IF_EQZ, 0, original));
        logger.log("已在现有 onCreate 注入 OBB 载荷");
        return new ImmutableMethod(
                method.getDefiningClass(),
                method.getName(),
                method.getParameters(),
                method.getReturnType(),
                method.getAccessFlags(),
                method.getAnnotations(),
                method.getHiddenApiRestrictions(),
                mutable);
    }

    private Method createOnCreate(ClassDef classDef) {
        MutableMethodImplementation mutable = new MutableMethodImplementation(3);
        ImmutableMethodReference handler = payloadHandlerReference();
        ImmutableMethodReference superOnCreate = new ImmutableMethodReference(
                classDef.getSuperclass(),
                "onCreate",
                Collections.singletonList("Landroid/os/Bundle;"),
                "V");
        mutable.addInstruction(new BuilderInstruction3rc(
                Opcode.INVOKE_STATIC_RANGE, 1, 1, handler));
        mutable.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
        mutable.addInstruction(new BuilderInstruction10x(Opcode.NOP));
        mutable.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        mutable.addInstruction(new BuilderInstruction3rc(
                Opcode.INVOKE_SUPER_RANGE, 1, 2, superOnCreate));
        mutable.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        Label callSuper = mutable.newLabelForIndex(4);
        mutable.replaceInstruction(2, new BuilderInstruction21t(Opcode.IF_EQZ, 0, callSuper));
        logger.log("启动类没有 onCreate，已创建代理 onCreate");
        return new ImmutableMethod(
                classDef.getType(),
                "onCreate",
                Collections.singletonList(new ImmutableMethodParameter(
                        "Landroid/os/Bundle;", Collections.emptySet(), "savedInstanceState")),
                "V",
                AccessFlags.PROTECTED.getValue(),
                Collections.emptySet(),
                Collections.emptySet(),
                mutable);
    }

    private ImmutableMethodReference payloadHandlerReference() {
        return new ImmutableMethodReference(
                PAYLOAD_CLASS,
                "handle",
                Collections.singletonList("Landroid/app/Activity;"),
                "Z");
    }

    private void buildUnsignedApk(File input, File output, String patchedDexName,
                                  byte[] patchedDex, byte[] payloadDex) throws Exception {
        int maxDex = 1;
        try (ZipFile zip = new ZipFile(input)) {
            Enumeration<? extends ZipEntry> scan = zip.entries();
            while (scan.hasMoreElements()) {
                String name = scan.nextElement().getName();
                if (name.matches("classes\\d*\\.dex")) {
                    String number = name.substring(7, name.length() - 4);
                    maxDex = Math.max(maxDex, number.isEmpty() ? 1 : Integer.parseInt(number));
                }
            }
        }
        String payloadName = "classes" + (maxDex + 1) + ".dex";
        if (output.exists() && !output.delete()) {
            throw new IOException("无法清理旧的临时 APK");
        }
        ZipSource source = new ZipSource(input.toPath());
        for (Entry entry : source.entries().values()) {
            String name = entry.getName();
            if (isOldSignature(name) || patchedDexName.equals(name)
                    || payloadName.equals(name)) continue;
            long alignment = entry.isCompressed() || entry.isDirectory()
                    ? 0 : (name.startsWith("lib/") && name.endsWith(".so") ? 4096 : 4);
            source.select(name, name, ZipSource.COMPRESSION_NO_CHANGE, alignment);
        }
        try (ZipArchive archive = new ZipArchive(output)) {
            archive.add(source);
            archive.add(new BytesSource(
                    patchedDex, patchedDexName, Deflater.DEFAULT_COMPRESSION));
            archive.add(new BytesSource(
                    payloadDex, payloadName, Deflater.DEFAULT_COMPRESSION));
        }
        logger.log("APK 已保留原压缩方式并完成原生库对齐");
        logger.log("载荷 DEX 已加入为 " + payloadName);
    }

    private boolean isOldSignature(String name) {
        String upper = name.toUpperCase();
        return upper.equals("META-INF/MANIFEST.MF")
                || (upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC")));
    }

    private void signApk(File input, File output) throws Exception {
        KeyStore.PrivateKeyEntry entry = getOrCreateSigningKey();
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                "OBBTool",
                entry.getPrivateKey(),
                Collections.singletonList((X509Certificate) entry.getCertificate()))
                .build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(input)
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .build()
                .sign();
        logger.log("修补 APK 已重新签名");
    }

    private KeyStore.PrivateKeyEntry getOrCreateSigningKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            Calendar start = Calendar.getInstance();
            Calendar end = Calendar.getInstance();
            end.add(Calendar.YEAR, 30);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            generator.initialize(new KeyPairGeneratorSpec.Builder(context)
                    .setAlias(KEY_ALIAS)
                    .setSubject(new X500Principal("CN=Android OBB Toolbox"))
                    .setSerialNumber(BigInteger.ONE)
                    .setStartDate(start.getTime())
                    .setEndDate(end.getTime())
                    .build());
            generator.generateKeyPair();
        }
        return (KeyStore.PrivateKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(in, out);
            return out.toByteArray();
        }
    }

    private static void copy(InputStream in, java.io.OutputStream out) throws IOException {
        byte[] buffer = new byte[1024 * 1024];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
    }

    private static final class PatchedDex {
        final String entryName;
        final byte[] data;

        PatchedDex(String entryName, byte[] data) {
            this.entryName = entryName;
            this.data = data;
        }
    }
}
