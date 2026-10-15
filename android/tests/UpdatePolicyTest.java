package app.voiceflow;

public final class UpdatePolicyTest {
    private static final String URL="https://github.com/JuelHossain/Vonotation/releases/download/v0.1.9/VonoTation.apk";
    private static final String HASH="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    public static void main(String[] args){
        UpdatePolicy.validate(81,"0.1.9-preview",URL,HASH,100);
        reject(()->UpdatePolicy.validate(0,"0.1.9",URL,HASH,100));reject(()->UpdatePolicy.validate(81,"",URL,HASH,100));reject(()->UpdatePolicy.validate(81,"bad\nname\n",URL,HASH,100));
        reject(()->UpdatePolicy.validate(81,"0.1.9",URL,"",100));reject(()->UpdatePolicy.validate(81,"0.1.9",URL,HASH,0));
        reject(()->UpdatePolicy.validate(81,"0.1.9",URL,HASH,UpdatePolicy.MAX_APK+1L));
        check(!UpdatePolicy.secureUrl("http://github.com/file.apk"));check(!UpdatePolicy.secureUrl("https://github.com.evil.test/file.apk"));
        check(!UpdatePolicy.secureUrl("https://user@github.com/file.apk"));check(!UpdatePolicy.secureUrl("https://github.com:444/file.apk"));
        check(!UpdatePolicy.secureUrl("file:///data/local/update.apk"));check(!UpdatePolicy.secureUrl("https://github.com/file.apk#fragment"));
        check(UpdatePolicy.secureUrl("https://release-assets.githubusercontent.com/asset?signature=signed"));
        check(UpdatePolicy.bounded(100,100));check(!UpdatePolicy.bounded(101,100));check(!UpdatePolicy.bounded(-1,100));
        check(UpdatePolicy.integrity(100,100,HASH,HASH.toUpperCase()));check(!UpdatePolicy.integrity(99,100,HASH,HASH));
        check(!UpdatePolicy.integrity(100,100,HASH,"f"+HASH.substring(1)));
        byte[] a={1,2},b={3,4};check(UpdatePolicy.sameSigners(new byte[][]{a,b},new byte[][]{b,a}));
        check(!UpdatePolicy.sameSigners(new byte[][]{a,b},new byte[][]{a,a}));check(!UpdatePolicy.sameSigners(new byte[][]{a},new byte[][]{b}));check(!UpdatePolicy.sameSigners(new byte[0][],new byte[0][]));
        check(UpdatePolicy.sessionMustWait(true,true,1,600001));check(UpdatePolicy.sessionMustWait(true,true,0,600001));
        check(UpdatePolicy.sessionMustWait(true,false,1000,2000));check(!UpdatePolicy.sessionMustWait(true,false,1000,61000));check(!UpdatePolicy.sessionMustWait(false,true,1000,2000));
        equal("000fff",UpdatePolicy.hex(new byte[]{0,15,(byte)255}));
        System.out.println("PASS: release schema bounds, HTTPS GitHub allowlist, size/hash integrity and exact signer matching and active installer protection");
    }
    static void reject(Runnable action){try{action.run();throw new AssertionError("Accepted invalid metadata");}catch(IllegalArgumentException expected){}}
    static void check(boolean good){if(!good)throw new AssertionError();}
    static void equal(String expected,String actual){if(!expected.equals(actual))throw new AssertionError();}
}
