package app.voiceflow;

import java.net.URI;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/** Android-free update validation; only release metadata and APK bytes enter this policy. */
final class UpdatePolicy {
    static final int MAX_FEED=16*1024,MAX_APK=40*1024*1024,MAX_REDIRECTS=5;
    private UpdatePolicy(){}
    static String feed(boolean notes){return "https://raw.githubusercontent.com/junior-rahman/Vonotation/main/"+(notes?"vocading-latest.json":"latest.json");}
    static void validatePackage(String applicationId,boolean notes){
        if(!(notes?"app.vonotation.notes":"app.voiceflow").equals(applicationId))throw new IllegalArgumentException("The update information belongs to a different app.");
    }
    static void validate(long code,String name,String address,String hash,long size){
        if(code<=0||code>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid update version.");
        if(!validName(name))throw new IllegalArgumentException("Invalid update name.");
        if(!secureUrl(address))throw new IllegalArgumentException("The update address is not a secure GitHub release.");
        if(hash==null||!hash.matches("[0-9a-fA-F]{64}"))throw new IllegalArgumentException("Update integrity information is missing.");
        if(size<=0||size>MAX_APK)throw new IllegalArgumentException("Invalid update file size.");
    }
    private static boolean validName(String name){
        if(name==null||name.trim().isEmpty()||name.length()>80)return false;
        for(int i=0;i<name.length();i++)if(Character.isISOControl(name.charAt(i)))return false;
        return true;
    }
    static boolean secureUrl(String address){
        if(address==null||address.length()>8192)return false;
        try{
            URI uri=new URI(address);String host=uri.getHost();
            if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||uri.getUserInfo()!=null||uri.getFragment()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))return false;
            host=host.toLowerCase(Locale.ROOT);
            return host.equals("github.com")||host.equals("raw.githubusercontent.com")||host.equals("objects.githubusercontent.com")||host.equals("release-assets.githubusercontent.com")||host.equals("github-releases.githubusercontent.com");
        }catch(Exception invalid){return false;}
    }
    /** Active OS installs may optimize code for longer than a minute and must never be replaced. */
    static boolean sessionMustWait(boolean exists,boolean active,long started,long now){return exists&&(active||(started>0&&now>=started&&now-started<60000));}
    static boolean bounded(long received,long limit){return received>=0&&limit>0&&received<=limit;}
    static boolean integrity(long received,long expected,String actual,String wanted){return received==expected&&actual!=null&&wanted!=null&&MessageDigest.isEqual(actual.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII),wanted.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
    static boolean sameSigners(byte[][] installed,byte[][] downloaded){
        if(installed==null||downloaded==null||installed.length==0||installed.length!=downloaded.length)return false;
        boolean[] used=new boolean[downloaded.length];
        for(byte[] signer:installed){if(signer==null||signer.length==0)return false;boolean found=false;for(int i=0;i<downloaded.length;i++)if(!used[i]&&Arrays.equals(signer,downloaded[i])){used[i]=true;found=true;break;}if(!found)return false;}
        return true;
    }
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte value:bytes){int n=value&255;out.append(Character.forDigit(n>>>4,16)).append(Character.forDigit(n&15,16));}return out.toString();}
}
