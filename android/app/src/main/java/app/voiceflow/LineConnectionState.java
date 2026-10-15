package app.voiceflow;

/** Keeps delayed checks for an old wireless-debugging port from changing a newer connection. */
final class LineConnectionState {
    enum Result { STALE, READY, UNAVAILABLE, RETRY_AUTH, REPAIR }
    private String host;
    private int port, rejected;

    boolean select(String nextHost,int nextPort) {
        if(matches(nextHost,nextPort))return false;
        host=nextHost;port=nextPort;rejected=0;
        return true;
    }

    boolean matches(String checkedHost,int checkedPort) {
        return host!=null && host.equals(checkedHost) && port==checkedPort;
    }

    Result checked(String checkedHost,int checkedPort,String state) {
        if(!matches(checkedHost,checkedPort))return Result.STALE;
        if("ok".equals(state)){rejected=0;return Result.READY;}
        if(!"auth".equals(state)){rejected=0;return Result.UNAVAILABLE;}
        return ++rejected>=3?Result.REPAIR:Result.RETRY_AUTH;
    }

    void clear(){host=null;port=0;rejected=0;}
}
