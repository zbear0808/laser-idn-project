package laser_show.profiling;

import jdk.jfr.*;

/**
 * JFR event for tracking IDN streaming timing.
 * 
 * Records the timing and size of IDN packets being sent
 * to laser projectors, useful for identifying network bottlenecks.
 */
@Name("laser_show.IdnStreaming")
@Label("IDN Packet Sent")
@Category({"Laser Show", "Network"})
@Description("Timing and size of IDN packets sent to projectors")
@StackTrace(false)
public class IdnStreamingEvent extends Event {
    
    @Label("Frame Interval")
    @Description("Time since last frame was sent (microseconds)")
    @Timespan(Timespan.MICROSECONDS)
    public long intervalUs;
    
    @Label("Packet Size")
    @Description("Size of the IDN packet in bytes")
    @DataAmount
    public long packetBytes;
    
    @Label("Point Count")
    @Description("Number of laser points in the packet")
    public int pointCount;
    
    @Label("Target Host")
    @Description("IP address of the target projector")
    public String targetHost;
    
    /**
     * Create and commit an IDN streaming event.
     * 
     * @param intervalUs Time since last frame (microseconds)
     * @param packetBytes Size of the packet in bytes
     * @param pointCount Number of points in the frame
     * @param targetHost Target projector IP address
     */
    public static void emit(long intervalUs, long packetBytes, int pointCount, String targetHost) {
        IdnStreamingEvent event = new IdnStreamingEvent();
        event.intervalUs = intervalUs;
        event.packetBytes = packetBytes;
        event.pointCount = pointCount;
        event.targetHost = targetHost;
        event.commit();
    }
}
