# YDoTool builds KEY_* names from user/layout input. getDeclaredField is not a
# closed set of literal lookups: retain every public Linux input constant and
# its original name/value, including aliases. No other app class is kept here.
-keepclassmembers class net.droopia.xmouse_ng.InputEventCodes {
    public static int KEY_*;
}

# mwiede JSch 2.28.8: Session/KeyExchange/KeyPair use JSch.getConfig(name),
# Class.forName and a no-argument constructor. Keep the implementation names
# and constructors, not all JSch members/protocol classes. Some constructors
# (CipherNone and authentication implementations) are PACKAGE-private. Interface
# calls/normal reachability retain their methods; optimization is allowed.
# Reflective constructors are invoked by Session/KeyPair in this same package.
# They must not be repackaged away from package-private auth/cipher classes.
-keeppackagenames com.jcraft.jsch
-keep,allowoptimization class com.jcraft.jsch.** implements com.jcraft.jsch.Cipher { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.** implements com.jcraft.jsch.HASH { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.** implements com.jcraft.jsch.MAC { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.** implements com.jcraft.jsch.Random { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.** implements com.jcraft.jsch.Compression { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.** implements com.jcraft.jsch.KDF { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.** implements com.jcraft.jsch.KEM { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.** implements com.jcraft.jsch.DH { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.** implements com.jcraft.jsch.ECDH { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.** implements com.jcraft.jsch.XDH { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.** implements com.jcraft.jsch.Signature { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.** implements com.jcraft.jsch.KeyPairGenRSA { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.** implements com.jcraft.jsch.KeyPairGenDSA { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.** implements com.jcraft.jsch.KeyPairGenECDSA { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.** implements com.jcraft.jsch.KeyPairGenEdDSA { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.** extends com.jcraft.jsch.KeyExchange { <init>(); }
# The backend explicitly selects password or publickey, with no GSSAPI/UserInfo.
-keep,allowoptimization class com.jcraft.jsch.UserAuthNone { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.UserAuthPassword { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.UserAuthPublicKey { <init>(); }
-keep,allowoptimization class com.jcraft.jsch.UserAuthKeyboardInteractive { <init>(); }

# BoundedSshKey creates a private BC provider instance, never registers it.
# BC constructs <package> + algorithm + "$Mappings", then JCA loads the
# registered SPI name. Its only production JCA request is AES/CBC/PKCS7Padding;
# other BC key/PEM/KDF/EdDSA paths use directly referenced lightweight APIs.
# AES's PREFIX uses AES.class.getName(), so its outer name must agree with the
# preserved nested SPI names. Do not retain every BC provider/algorithm/member.
-keepnames class org.bouncycastle.jcajce.provider.symmetric.AES
-keep,allowoptimization class org.bouncycastle.jcajce.provider.symmetric.AES$Mappings { public <init>(); }
-keep,allowoptimization class org.bouncycastle.jcajce.provider.symmetric.AES$ECB { public <init>(); }
-keep,allowoptimization class org.bouncycastle.jcajce.provider.symmetric.AES$AlgParams { public <init>(); }

# dnsjava 3.6.5's A/AAAA codec uses constructor references (Type's factories),
# not reflective record names. No DNS-wide keep rule or resolver SPI is needed.
# dnsjava's Lombok @Generated is a build-time (RuntimeInvisible) marker, not a
# runtime dependency. SLF4J 1.7 deliberately catches the absent optional binding
# and uses its NOP logger; the address codec does not install a logging backend.
-dontwarn lombok.Generated
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Android manifest/XML entry points and onClick methods are retained by AGP's
# generated AAPT rules plus proguard-android-optimize.txt and AndroidX consumers.
