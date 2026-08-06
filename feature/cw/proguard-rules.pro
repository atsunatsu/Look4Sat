# Look4Sat Pro — CW 解码模块(照搬 Morse Expert 1.15)混淆规则
# 所有 CW 类保持原名: pas.* 通过 RegisterNatives 按类名注册 JNI(混淆即崩);
# 其余为照搬的混淆名类(命名已无混淆意义, 保持以保逻辑完整)。

# JNI 引擎(RegisterNatives 按类名/方法名查表)
-keep class pas.** { *; }

# 照搬的解码/UI 类(全部保持)
-keep class com.ve3nea.morse_expert.** { *; }
-keep class H2.** { *; }
-keep class I2.b { *; }
-keep class J2.** { *; }
-keep class k3.** { *; }
-keep class i3.** { *; }
-keep class g3.** { *; }
-keep class j3.** { *; }
-keep class s.** { *; }
-keep class d1.AbstractC1518b { *; }
-keep class B0.b { *; }
-keep class B.RunnableC0001b { *; }
-keep class D.n { *; }
-keep class E2.g { *; }
-keep class F2.a { *; }
-keep class I0.d { *; }
-keep class K1.a { *; }
-keep class j1.C1646n { *; }

# sun.misc stub(Android 无此类, 仅编译期; 防止 R8 异常处理)
-dontwarn sun.misc.**
-keep class sun.misc.Unsafe { *; }
-keep class sun.misc.Cleaner { *; }
