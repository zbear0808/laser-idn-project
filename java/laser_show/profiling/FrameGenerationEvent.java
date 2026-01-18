package laser_show.profiling;

import jdk.jfr.*;

/**
 * JFR event for tracking frame generation timing.
 * 
 * Records base frame generation time, effect chain application time,
 * and associated metadata for performance analysis.
 */
@Name("laser_show.FrameGeneration")
@Label("Frame Generation")
@Category({"Laser Show", "Frame"})
@Description("Timing for a single frame generation including base frame and effects")
@StackTrace(false)
public class FrameGenerationEvent extends Event {
    
    @Label("Base Frame Time")
    @Description("Time to generate the base frame from presets (microseconds)")
    @Timespan(Timespan.MICROSECONDS)
    public long baseTimeUs;
    
    @Label("Effects Time")
    @Description("Time to apply effect chain (microseconds)")
    @Timespan(Timespan.MICROSECONDS)
    public long effectsTimeUs;
    
    @Label("Total Time")
    @Description("Total frame generation time (microseconds)")
    @Timespan(Timespan.MICROSECONDS)
    public long totalTimeUs;
    
    @Label("Effect Count")
    @Description("Number of effects in the chain")
    public int effectCount;
    
    @Label("Point Count")
    @Description("Number of laser points in the frame")
    public int pointCount;
    
    /**
     * Create and commit a frame generation event.
     * 
     * @param baseTimeUs Time for base frame generation (microseconds)
     * @param effectsTimeUs Time for effect chain application (microseconds)
     * @param effectCount Number of effects applied
     * @param pointCount Number of points in the frame
     */
    public static void emit(long baseTimeUs, long effectsTimeUs, int effectCount, int pointCount) {
        FrameGenerationEvent event = new FrameGenerationEvent();
        event.baseTimeUs = baseTimeUs;
        event.effectsTimeUs = effectsTimeUs;
        event.totalTimeUs = baseTimeUs + effectsTimeUs;
        event.effectCount = effectCount;
        event.pointCount = pointCount;
        event.commit();
    }
}
