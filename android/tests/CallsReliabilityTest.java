package app.voiceflow;

import java.io.File;

public final class CallsReliabilityTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        check(!CallSessionPolicy.mayStart(false,true),"translation expectation cannot bypass Calls off");
        check(!CallSessionPolicy.mayStart(true,false),"ringing/idle cannot start recording");
        check(CallSessionPolicy.mayStart(true,true),"enabled connected call can start");
        File base=new File("notes/123-wire.m4a");
        check(CallAudioSegments.attempt(base,0).getPath().equals("notes/123-wire-0.m4a"),"first wireless attempt owns its file");
        check(CallAudioSegments.attempt(base,1).getPath().equals("notes/123-wire-1.m4a"),"retry uses a separate file");
        check(!CallAudioSegments.attempt(base,0).equals(new File("notes/123-0.m4a")),"wireless cannot overwrite microphone fallback segment");
        CallSessionState session=new CallSessionState();
        session.begin(123);
        long oldEpoch=session.epoch();
        session.queueStart();
        check(!session.queued(),"duplicate OFFHOOK cannot queue a second active call");
        session.finish();session.queueStart();
        check(session.queued(),"next OFFHOOK waits while old source finalizes");
        check(session.id()==123,"old source handoff retains old call identity");
        session.begin(456);
        check(session.id()==456 && !session.queued(),"next call gets a fresh recording identity");
        check(!session.owns(123,oldEpoch),"old delayed teardown cannot close next call");
        check(session.owns(456,session.epoch()),"new call owns only its own cleanup");
        long started=1000000,ended=1060000;
        check(CallSessionPolicy.callLogDistance(started,ended,started,60)==0,"same connected call log matches");
        check(CallSessionPolicy.callLogDistance(started,ended,started-10000,70)==10000,"ringing lead-in can match same end");
        check(CallSessionPolicy.callLogDistance(started,ended,started-60000,10)==Long.MAX_VALUE,"previous call does not match ending call");
        check(CallSessionPolicy.callLogDistance(started,ended,ended+6000,0)==Long.MAX_VALUE,"future call is excluded");
        LineNoticePolicy policy=new LineNoticePolicy();
        check(!policy.healthy(1000),"one successful probe cannot rearm outage notice");
        check(!policy.healthy(31000),"brief recovery cannot rearm");
        policy.outage();
        check(!policy.healthy(61000),"flap restarts sustained recovery window");
        check(policy.healthy(121000),"verified healthy minute rearms outage state");
        long now=10000000;
        check(LineNoticePolicy.shouldNotify(now,0,false),"first outage can notify");
        check(!LineNoticePolicy.shouldNotify(now,0,true),"same persisted outage never repeats");
        check(!LineNoticePolicy.shouldNotify(now+5*60*1000,now,false),"five-minute flap remains quiet after recovery");
        check(!LineNoticePolicy.shouldNotify(now+10*60*1000,now,false),"ten-minute flap remains quiet after recovery");
        check(LineNoticePolicy.shouldNotify(now+LineNoticePolicy.NOTICE_COOLDOWN_MS,now,false),"new sustained outage can notify after cooldown");
        System.out.println("PASS: "+checks+" call lifecycle and notification checks.");
    }
}
