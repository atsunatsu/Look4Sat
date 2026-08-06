package g3;

/* loaded from: classes.dex */
public abstract class c {

    /* renamed from: a, reason: collision with root package name */
    public static final double[][] f11585a;

    /* renamed from: b, reason: collision with root package name */
    public static final double[] f11586b;
    public static final double[] c;

    /* renamed from: d, reason: collision with root package name */
    public static final double[] f11587d;

    /* renamed from: e, reason: collision with root package name */
    public static final double[] f11588e;
    public static final long[] f;

    /* renamed from: g, reason: collision with root package name */
    public static final long[] f11589g;

    /* renamed from: h, reason: collision with root package name */
    public static final double[] f11590h;

    static {
        StrictMath.log(Double.MAX_VALUE);
        f11585a = new double[][]{new double[]{1.0d, 5.669184079525E-24d}, new double[]{-0.25d, -0.25d}, new double[]{0.3333333134651184d, 1.986821492305628E-8d}, new double[]{-0.25d, -6.663542893624021E-14d}, new double[]{0.19999998807907104d, 1.1921056801463227E-8d}, new double[]{-0.1666666567325592d, -7.800414592973399E-9d}, new double[]{0.1428571343421936d, 5.650007086920087E-9d}, new double[]{-0.12502530217170715d, -7.44321345601866E-11d}, new double[]{0.11113807559013367d, 9.219544613762692E-9d}};
        f11586b = new double[]{0.0d, 0.1246747374534607d, 0.24740394949913025d, 0.366272509098053d, 0.4794255495071411d, 0.5850973129272461d, 0.6816387176513672d, 0.7675435543060303d, 0.8414709568023682d, 0.902267575263977d, 0.9489846229553223d, 0.9808930158615112d, 0.9974949359893799d, 0.9985313415527344d};
        c = new double[]{0.0d, -4.068233003401932E-9d, 9.755392680573412E-9d, 1.9987994582857286E-8d, -1.0902938113007961E-8d, -3.9986783938944604E-8d, 4.23719669792332E-8d, -5.207000323380292E-8d, 2.800552834259E-8d, 1.883511811213715E-8d, -3.5997360512765566E-9d, 4.116164446561962E-8d, 5.0614674548127384E-8d, -1.0129027912496858E-9d};
        f11587d = new double[]{1.0d, 0.9921976327896118d, 0.9689123630523682d, 0.9305076599121094d, 0.8775825500488281d, 0.8109631538391113d, 0.7316888570785522d, 0.6409968137741089d, 0.5403022766113281d, 0.4311765432357788d, 0.3153223395347595d, 0.19454771280288696d, 0.07073719799518585d, -0.05417713522911072d};
        f11588e = new double[]{0.0d, 3.4439717236742845E-8d, 5.865827662008209E-8d, -3.7999795083850525E-8d, 1.184154459111628E-8d, -3.43338934259355E-8d, 1.1795268640216787E-8d, 4.438921624363781E-8d, 2.925681159240093E-8d, -2.6437112632041807E-8d, 2.2860509143963117E-8d, -4.813899778443457E-9d, 3.6725170580355583E-9d, 2.0217439756338078E-10d};
        f = new long[]{2935890503282001226L, 9154082963658192752L, 3952090531849364496L, 9193070505571053912L, 7910884519577875640L, 113236205062349959L, 4577762542105553359L, -5034868814120038111L, 4208363204685324176L, 5648769086999809661L, 2819561105158720014L, -4035746434778044925L, -302932621132653753L, -2644281811660520851L, -3183605296591799669L, 6722166367014452318L, -3512299194304650054L, -7278142539171889152L};
        f11589g = new long[]{-3958705157555305932L, -4267615245585081135L};
        f11590h = new double[]{0.0d, 0.125d, 0.25d, 0.375d, 0.5d, 0.625d, 0.75d, 0.875d, 1.0d, 1.125d, 1.25d, 1.375d, 1.5d, 1.625d};
    }

    public static double a(double d4) {
        double d5;
        if (d4 != d4) {
            return d4;
        }
        if (d4 == d4 && d4 < 4.503599627370496E15d && d4 > -4.503599627370496E15d) {
            long j4 = (long) d4;
            if (d4 < 0.0d && j4 != d4) {
                j4--;
            }
            if (j4 == 0) {
                d5 = j4 * d4;
            } else {
                d5 = j4;
            }
        } else {
            d5 = d4;
        }
        if (d5 == d4) {
            return d5;
        }
        double d6 = d5 + 1.0d;
        if (d6 == 0.0d) {
            return d4 * d6;
        }
        return d6;
    }

    public static double b(double d4) {
        double g4;
        double d5 = 0.0d;
        if (d4 < 0.0d) {
            d4 = -d4;
        }
        if (d4 != d4 || d4 == Double.POSITIVE_INFINITY) {
            return Double.NaN;
        }
        int i4 = 0;
        if (d4 > 3294198.0d) {
            double[] dArr = new double[3];
            e(d4, dArr);
            i4 = ((int) dArr[0]) & 3;
            d4 = dArr[1];
            d5 = dArr[2];
        } else if (d4 > 1.5707963267948966d) {
            a aVar = new a(d4);
            i4 = aVar.f11582a & 3;
            d4 = aVar.f11583b;
            d5 = aVar.c;
        }
        if (i4 != 0) {
            if (i4 != 1) {
                if (i4 != 2) {
                    if (i4 != 3) {
                        return Double.NaN;
                    }
                    return g(d4, d5);
                }
                g4 = c(d4, d5);
            } else {
                g4 = g(d4, d5);
            }
            return -g4;
        }
        return c(d4, d5);
    }

    public static double c(double d4, double d5) {
        double d6 = 1.5707963267948966d - d4;
        return g(d6, (6.123233995736766E-17d - d5) + (-((d6 - 1.5707963267948966d) + d4)));
    }

    public static double d(double d4) {
        if (d4 != 0.0d) {
            long doubleToRawLongBits = Double.doubleToRawLongBits(d4);
            if (((Long.MIN_VALUE & doubleToRawLongBits) != 0 || d4 != d4) && d4 != 0.0d) {
                return Double.NaN;
            }
            if (d4 == Double.POSITIVE_INFINITY) {
                return Double.POSITIVE_INFINITY;
            }
            int i4 = ((int) (doubleToRawLongBits >> 52)) - 1023;
            if ((9218868437227405312L & doubleToRawLongBits) == 0) {
                if (d4 == 0.0d) {
                    return Double.NEGATIVE_INFINITY;
                }
                doubleToRawLongBits <<= 1;
                while ((4503599627370496L & doubleToRawLongBits) == 0) {
                    i4--;
                    doubleToRawLongBits <<= 1;
                }
            }
            if ((i4 == -1 || i4 == 0) && d4 < 1.01d && d4 > 0.99d) {
                double d5 = d4 - 1.0d;
                double d6 = d5 * 1.073741824E9d;
                double d7 = (d5 + d6) - d6;
                double d8 = d5 - d7;
                double[][] dArr = f11585a;
                double[] dArr2 = dArr[dArr.length - 1];
                double d9 = dArr2[0];
                double d10 = dArr2[1];
                for (int length = dArr.length - 2; length >= 0; length--) {
                    double d11 = d9 * d7;
                    double d12 = (d10 * d8) + (d10 * d7) + (d9 * d8);
                    double d13 = d11 * 1.073741824E9d;
                    double d14 = (d11 + d13) - d13;
                    double d15 = (d11 - d14) + d12;
                    double[] dArr3 = dArr[length];
                    double d16 = d14 + dArr3[0];
                    double d17 = d15 + dArr3[1];
                    double d18 = d16 * 1.073741824E9d;
                    d9 = (d16 + d18) - d18;
                    d10 = (d16 - d9) + d17;
                }
                double d19 = d9 * d7;
                double d20 = (d10 * d8) + (d7 * d10) + (d9 * d8);
                double d21 = 1.073741824E9d * d19;
                double d22 = (d19 + d21) - d21;
                return (d19 - d22) + d20 + d22;
            }
            long j4 = 4499201580859392L & doubleToRawLongBits;
            double[] dArr4 = b.f11584a[(int) (j4 >> 42)];
            double d23 = (doubleToRawLongBits & 4398046511103L) / (j4 + 4.503599627370496E15d);
            double d24 = (((((((((((-0.16624882440418567d) * d23) + 0.19999954120254515d) * d23) - 0.2499999997677497d) * d23) + 0.3333333333332802d) * d23) - 0.5d) * d23) + 1.0d) * d23;
            double d25 = i4;
            double d26 = 0.6931470632553101d * d25;
            double d27 = dArr4[0];
            double d28 = d26 + d27;
            double d29 = (-((d28 - d26) - d27)) + 0.0d;
            double d30 = d28 + d24;
            double d31 = d29 + (-((d30 - d28) - d24));
            double d32 = d25 * 1.1730463525082348E-7d;
            double d33 = d30 + d32;
            double d34 = d31 + (-((d33 - d30) - d32));
            double d35 = dArr4[1];
            double d36 = d33 + d35;
            double d37 = d36 + 0.0d;
            return d34 + (-((d36 - d33) - d35)) + (-((d37 - d36) - 0.0d)) + d37;
        }
        return Double.NEGATIVE_INFINITY;
    }

    public static void e(double d4, double[] dArr) {
        long j4;
        long j5;
        long j6;
        boolean z3;
        boolean z4;
        boolean z5;
        boolean z6;
        boolean z7;
        boolean z8;
        boolean z9;
        boolean z10;
        boolean z11;
        boolean z12;
        boolean z13;
        boolean z14;
        boolean z15;
        boolean z16;
        boolean z17;
        boolean z18;
        boolean z19;
        boolean z20;
        boolean z21;
        boolean z22;
        boolean z23;
        long j7;
        long doubleToRawLongBits = Double.doubleToRawLongBits(d4);
        int i4 = ((int) ((doubleToRawLongBits >> 52) & 2047)) - 1022;
        long j8 = ((doubleToRawLongBits & 4503599627370495L) | 4503599627370496L) << 11;
        int i5 = i4 >> 6;
        int i6 = i4 - (i5 << 6);
        long[] jArr = f;
        if (i6 != 0) {
            if (i5 == 0) {
                j7 = 0;
            } else {
                j7 = jArr[i5 - 1] << i6;
            }
            long j9 = jArr[i5];
            int i7 = 64 - i6;
            j4 = j7 | (j9 >>> i7);
            long j10 = jArr[i5 + 1];
            j5 = (j9 << i6) | (j10 >>> i7);
            j6 = (jArr[i5 + 2] >>> i7) | (j10 << i6);
        } else {
            if (i5 == 0) {
                j4 = 0;
            } else {
                j4 = jArr[i5 - 1];
            }
            j5 = jArr[i5];
            j6 = jArr[i5 + 1];
        }
        long j11 = j8 >>> 32;
        long j12 = j8 & 4294967295L;
        long j13 = j5 >>> 32;
        long j14 = j5 & 4294967295L;
        long j15 = j11 * j13;
        long j16 = j12 * j14;
        long j17 = j13 * j12;
        long j18 = j14 * j11;
        long j19 = j16 + (j18 << 32);
        long j20 = j15 + (j18 >>> 32);
        if ((j16 & Long.MIN_VALUE) != 0) {
            z3 = true;
        } else {
            z3 = false;
        }
        if ((j18 & 2147483648L) != 0) {
            z4 = true;
        } else {
            z4 = false;
        }
        long j21 = j19 & Long.MIN_VALUE;
        if (j21 != 0) {
            z5 = true;
        } else {
            z5 = false;
        }
        if ((z3 && z4) || ((z3 || z4) && !z5)) {
            j20++;
        }
        if (j21 != 0) {
            z6 = true;
        } else {
            z6 = false;
        }
        if ((j17 & 2147483648L) != 0) {
            z7 = true;
        } else {
            z7 = false;
        }
        long j22 = j19 + (j17 << 32);
        long j23 = j20 + (j17 >>> 32);
        long j24 = j22 & Long.MIN_VALUE;
        if (j24 != 0) {
            z8 = true;
        } else {
            z8 = false;
        }
        if ((z6 && z7) || ((z6 || z7) && !z8)) {
            j23++;
        }
        long j25 = j6 >>> 32;
        long j26 = (j11 * j25) + ((((j6 & 4294967295L) * j11) + (j25 * j12)) >>> 32);
        if (j24 != 0) {
            z9 = true;
        } else {
            z9 = false;
        }
        if ((j26 & Long.MIN_VALUE) != 0) {
            z10 = true;
        } else {
            z10 = false;
        }
        long j27 = j22 + j26;
        if ((j27 & Long.MIN_VALUE) != 0) {
            z11 = true;
        } else {
            z11 = false;
        }
        if ((z9 && z10) || ((z9 || z10) && !z11)) {
            j23++;
        }
        long j28 = j4 >>> 32;
        long j29 = j4 & 4294967295L;
        long j30 = (j12 * j29) + (((j11 * j29) + (j12 * j28)) << 32) + j23;
        int i8 = (int) (j30 >>> 62);
        long j31 = (j27 >>> 62) | (j30 << 2);
        long j32 = j27 << 2;
        long j33 = j31 >>> 32;
        long j34 = j31 & 4294967295L;
        long[] jArr2 = f11589g;
        long j35 = jArr2[0];
        long j36 = j35 >>> 32;
        long j37 = j35 & 4294967295L;
        long j38 = j34 * j37;
        long j39 = j34 * j36;
        long j40 = j33 * j37;
        long j41 = j38 + (j40 << 32);
        long j42 = (j33 * j36) + (j40 >>> 32);
        if ((j38 & Long.MIN_VALUE) != 0) {
            z12 = true;
        } else {
            z12 = false;
        }
        if ((j40 & 2147483648L) != 0) {
            z13 = true;
        } else {
            z13 = false;
        }
        long j43 = j41 & Long.MIN_VALUE;
        if (j43 != 0) {
            z14 = true;
        } else {
            z14 = false;
        }
        if ((z12 && z13) || ((z12 || z13) && !z14)) {
            j42++;
        }
        if (j43 != 0) {
            z15 = true;
        } else {
            z15 = false;
        }
        if ((j39 & 2147483648L) != 0) {
            z16 = true;
        } else {
            z16 = false;
        }
        long j44 = j41 + (j39 << 32);
        long j45 = j42 + (j39 >>> 32);
        long j46 = j44 & Long.MIN_VALUE;
        if (j46 != 0) {
            z17 = true;
        } else {
            z17 = false;
        }
        if ((z15 && z16) || ((z15 || z16) && !z17)) {
            j45++;
        }
        long j47 = jArr2[1];
        long j48 = j47 >>> 32;
        long j49 = (j33 * j48) + (((j33 * (j47 & 4294967295L)) + (j34 * j48)) >>> 32);
        if (j46 != 0) {
            z18 = true;
        } else {
            z18 = false;
        }
        if ((j49 & Long.MIN_VALUE) != 0) {
            z19 = true;
        } else {
            z19 = false;
        }
        long j50 = j44 + j49;
        long j51 = j50 & Long.MIN_VALUE;
        if (j51 != 0) {
            z20 = true;
        } else {
            z20 = false;
        }
        if ((z18 && z19) || ((z18 || z19) && !z20)) {
            j45++;
        }
        long j52 = j32 >>> 32;
        long j53 = (j52 * j36) + (((j52 * j37) + ((j32 & 4294967295L) * j36)) >>> 32);
        if (j51 != 0) {
            z21 = true;
        } else {
            z21 = false;
        }
        if ((j53 & Long.MIN_VALUE) != 0) {
            z22 = true;
        } else {
            z22 = false;
        }
        if (((j50 + j53) & Long.MIN_VALUE) != 0) {
            z23 = true;
        } else {
            z23 = false;
        }
        if ((z21 && z22) || ((z21 || z22) && !z23)) {
            j45++;
        }
        double d5 = (j45 >>> 12) / 4.503599627370496E15d;
        double d6 = ((((j45 & 4095) << 40) + (f11589g[0] >>> 24)) / 4.503599627370496E15d) / 4.503599627370496E15d;  // r34 = f11589g[0](smali: aget-wide v5, v2)
        double d7 = d5 + d6;
        dArr[0] = i8;
        dArr[1] = d7 * 2.0d;
        dArr[2] = (-((d7 - d5) - d6)) * 2.0d;
    }

    /* JADX WARN: Removed duplicated region for block: B:20:0x005f  */
    /* JADX WARN: Removed duplicated region for block: B:22:0x0063  */
    /* JADX WARN: Removed duplicated region for block: B:32:0x007a  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public static double f(double d4) {
        boolean z3;
        double d5;
        double d6;
        double g4;
        int i4 = 0;
        double d7 = 0.0d;
        if (d4 < 0.0d) {
            d5 = -d4;
            z3 = true;
        } else {
            z3 = false;
            d5 = d4;
        }
        if (d5 == 0.0d) {
            if (Double.doubleToRawLongBits(d4) >= 0) {
                return 0.0d;
            }
            return -0.0d;
        }
        if (d5 != d5 || d5 == Double.POSITIVE_INFINITY) {
            return Double.NaN;
        }
        if (d5 > 3294198.0d) {
            double[] dArr = new double[3];
            e(d5, dArr);
            i4 = ((int) dArr[0]) & 3;
            d5 = dArr[1];
            d6 = dArr[2];
        } else {
            if (d5 > 1.5707963267948966d) {
                a aVar = new a(d5);
                int i5 = aVar.f11582a & 3;
                d5 = aVar.f11583b;
                d6 = aVar.c;
                i4 = i5;
            }
            if (z3) {
                i4 ^= 2;
            }
            // smali 证实: f() = sin(象限 switch!):
            //   case 0 -> g(d5,0)(sin(0-π/2))
            //   case 1 -> c(d5,0)(sin(π/2-π) = cos(余角))
            //   case 2 -> -g(d5,0)(sin(π-3π/2) = -sin(余角))
            //   case 3 -> -c(d5,0)(sin(3π/2-2π) = -cos(余角))
            // jadx 畸形嵌套 if 把 case 0 还原成 return NaN(小角度全 NaN!),
            // 且 case 1/2/3 全走 g() —— 旋转因子表 329 个 NaN + 频谱错乱的根源!
            if (i4 == 0) {
                return g(d5, d7);
            }
            if (i4 == 1) {
                return c(d5, d7);
            }
            if (i4 == 2) {
                return -g(d5, d7);
            }
            return -c(d5, d7);
        }
        d7 = d6;
        // jadx 还原不完整(smali 650 行, jadx 只还原 63 行); f() 未被任何代码调用(死代码),
        // 按 smali 尾部语义补 return(cond_0: v22 = v22 + v14; return v22)
        return d7;
    }

    public static double g(double d4, double d5) {
        int i4 = (int) ((8.0d * d4) + 0.5d);
        double d6 = d4 - f11590h[i4];
        double d7 = f11586b[i4];
        double d8 = c[i4];
        double d9 = f11587d[i4];
        double d10 = f11588e[i4];
        double d11 = d6 * d6;
        double d12 = ((((((2.7553817452272217E-6d * d11) - 1.9841269659586505E-4d) * d11) + 0.008333333333329196d) * d11) - 0.16666666666666666d) * d11 * d6;
        double d13 = ((((((2.479773539153719E-5d * d11) - 0.0013888888689039883d) * d11) + 0.041666666666621166d) * d11) - 0.49999999999999994d) * d11;
        double d14 = 1.073741824E9d * d6;
        double d15 = (d6 + d14) - d14;
        double d16 = (d6 - d15) + d12;
        double d17 = d7 + 0.0d;
        double d18 = d9 * d15;
        double d19 = d17 + d18;
        double d20 = (d10 * d16) + (d8 * d13) + (d10 * d15) + (d9 * d16) + (d7 * d13) + (-((d17 - 0.0d) - d7)) + 0.0d + (-((d19 - d17) - d18)) + d8;
        if (d5 != 0.0d) {
            double d21 = (((d13 + 1.0d) * (d9 + d10)) - ((d15 + d16) * (d7 + d8))) * d5;
            double d22 = d19 + d21;
            d20 += -((d22 - d19) - d21);
            d19 = d22;
        }
        return d19 + d20;
    }
}
