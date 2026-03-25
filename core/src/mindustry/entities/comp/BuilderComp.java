package mindustry.entities.comp;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.struct.ObjectSet;
import arc.util.*;
import mindustry.*;
import mindustry.annotations.Annotations.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.*;
import mindustry.world.blocks.ConstructBlock.*;

import java.util.*;

import static mindustry.Vars.*;

@Component
abstract class BuilderComp implements Posc, Statusc, Teamc, Rotc{
    @Import float x, y, rotation, buildSpeedMultiplier;
    @Import UnitType type;
    @Import Team team;

    /** Build queue. Not actually a queue, but rather an unordered collection of build plans. */
    @SyncLocal ObjectSet<BuildPlan> plans = new ObjectSet<>(1);
    /** Next plan to build. Does NOT have to be the plan at the start of build queue. */
    @SyncLocal @Nullable BuildPlan nextPlan;
    @SyncLocal boolean updateBuilding = true;

    private transient float buildCounter;
    private transient BuildPlan lastActive;
    private transient int lastSize;
    transient float buildAlpha = 0f;

    public boolean canBuild(){
        return type.buildSpeed > 0 && buildSpeedMultiplier > 0;
    }

    @Override
    public void update(){
        updateBuildLogic();
    }

    @Override
    public void afterRead(){
        //why would this happen?
        if(plans == null){
            plans = new ObjectSet<>(1);
        }
    }

    public void validatePlans(){
        if(plans.size > 0){
            Iterator<BuildPlan> it = plans.iterator();
            while(it.hasNext()){
                BuildPlan plan = it.next();
                Tile tile = world.tile(plan.x, plan.y);
                boolean isSameDerelict = (tile != null && tile.build != null && tile.block() == plan.block && tile.build.tileX() == plan.x && tile.build.tileY() == plan.y && tile.team() == Team.derelict);
                if(tile == null || (plan.breaking && tile.block() == Blocks.air) || (!plan.breaking && ((tile.build != null && tile.build.rotation == plan.rotation && !isSameDerelict) || !plan.block.rotate) &&
                    //the block must be the same, but not derelict and the same
                    ((tile.block() == plan.block && !isSameDerelict) ||
                        //same floor or overlay
                        (plan.block != null && (plan.block.isOverlay() && plan.block == tile.overlay() || (plan.block.isFloor() && plan.block == tile.floor())))))){

                    it.remove();
                }
            }
        }
    }

    /** Adds plan to the queue. */
    public void addPlan(BuildPlan plan) {
        plans.add(plan);
    }

    /** Removes plan from the queue. */
    public void clearPlan(BuildPlan plan) {
        plans.remove(nextPlan);
        if(nextPlan == plan) {
            nextPlan = null;
        }
    }

    /** Removes all queued plans. */
    public void clearAllPlans() {
        plans.clear();
        nextPlan = null;
    }

    /** Removes next queued plan and REMOVES it from the queue. */
    public void clearNextPlanDequeue() {
        if(nextPlan != null) {
            clearPlan(nextPlan);
        }
    }

    /** Removes next queued plan but KEEPS it in queue. */
    public void clearNextPlanEnqueue() {
        if(nextPlan != null) {
            nextPlan = null;
        }
    }

    public void updateBuildLogic(){
        if(type.buildSpeed <= 0f) return;

        if(!headless){
            //visual activity update
            if(lastActive != null && buildAlpha <= 0.01f){
                lastActive = null;
            }

            buildAlpha = Mathf.lerpDelta(buildAlpha, activelyBuilding() ? 1f : 0f, 0.15f);
        }

        validatePlans();

        if(!updateBuilding || !canBuild()){
            return;
        }

        float finalPlaceDst = state.rules.infiniteResources ? Float.MAX_VALUE : type.buildRange;
        boolean infinite = state.rules.infiniteResources || team().rules().infiniteResources;

        buildCounter += Time.delta;
        if(Float.isNaN(buildCounter) || Float.isInfinite(buildCounter)) buildCounter = 0f;
        buildCounter = Math.min(buildCounter, 10f);

        boolean instant = state.rules.instantBuild && state.rules.infiniteResources;

        //random attempt to fix a freeze that only occurs on Android
        int maxPerFrame = instant ? plans.size : 10, count = 0;

        var core = core();

        if((core == null && !infinite)) return;

//        /** Plans that should be considered last for building. */
//        ObjectSet<BuildPlan> penalizedPlans =  new ObjectSet<>(1);
        /** Plans that should be excluded from consideration for construction. */
        ObjectSet<BuildPlan> excludePlans =  new ObjectSet<>(1);
        while((buildCounter >= 1 || instant) && count++ < maxPerFrame && plans.size > 0) {
            buildCounter -= 1f;

            // find the next build plan.
            // look for plans in range, pick the one with highest priority
            if (plans.size > 1) {
                BuildPlan bestPlan = null;
                float bestPlanDst = Float.MAX_VALUE;
                int bestPlanPriority = Integer.MIN_VALUE;
                for (BuildPlan plan : plans) {
                    if (excludePlans.contains(plan)) {
                        continue;
                    }

                    float dst = plan.dst2(this);
                    boolean within = dst <= finalPlaceDst * finalPlaceDst;
                    if (!within || shouldSkip(plan, core)) {
                        continue;
                    }

                    int priority = plan.priority();
                    if (priority < bestPlanPriority) {
                        continue;
                    } else if (priority > bestPlanPriority) {
                        bestPlan = plan;
                        bestPlanDst = dst;
                        bestPlanPriority = priority;
                        continue;
                    }

                    if (dst < bestPlanDst) {
                        bestPlan = plan;
                        bestPlanDst = dst;
                        bestPlanPriority = priority;
                    }
                }

                if (bestPlan != null) {
                    nextPlan = bestPlan;
                }
            }

            Tile tile = nextPlan.tile();

            lastActive = nextPlan;
            buildAlpha = 1f;
            if(nextPlan.breaking) lastSize = tile.block().size;

            if(!within(tile, finalPlaceDst)) continue;

            if(!headless){
                Vars.control.sound.loop(Sounds.loopBuild, tile, 1.3f);
            }

            if(!(tile.build instanceof ConstructBuild cb)){
                if(!nextPlan.initialized && !nextPlan.breaking && Build.validPlaceIgnoreUnits(nextPlan.block, team, nextPlan.x, nextPlan.y, nextPlan.rotation, true, true)){
                    if(Build.checkNoUnitOverlap(nextPlan.block, nextPlan.x, nextPlan.y)){
                        boolean hasAll = infinite || nextPlan.isRotation(team) ||
                        //derelict repair
                        (tile.team() == Team.derelict && tile.block() == nextPlan.block && tile.build != null && tile.block().allowDerelictRepair && state.rules.derelictRepair) ||
                        //make sure there's at least 1 item of each type first
                        !Structs.contains(nextPlan.block.requirements, i -> !core.items.has(i.item, Math.min(Mathf.round(i.amount * state.rules.buildCostMultiplier), 1)));

                        if(hasAll){
                            Call.beginPlace(self(), nextPlan.block, team, nextPlan.x, nextPlan.y, nextPlan.rotation, nextPlan.block.instantBuild ? nextPlan.config : null);

                            if(!net.client() && nextPlan.block.instantBuild){
                                if(plans.size > 0){
                                    clearNextPlanDequeue();
                                }
                                continue;
                            }
                        }else{
                            nextPlan.stuck = true;
                        }
                    }else{
                        //there's a unit blocking the plan, skip it
                        excludePlans.add(nextPlan);
                        continue;
                    }
                }else if(!nextPlan.initialized && nextPlan.breaking && Build.validBreak(team, nextPlan.x, nextPlan.y)){
                    Call.beginBreak(self(), team, nextPlan.x, nextPlan.y);
                }else{
                    clearNextPlanDequeue();
                    continue;
                }
            }else if((tile.team() != team && tile.team() != Team.derelict) || (!nextPlan.breaking && (cb.current != nextPlan.block || cb.tile != nextPlan.tile()))){
                clearNextPlanDequeue();
                continue;
            }

            if(tile.build instanceof ConstructBuild && !nextPlan.initialized){
                Events.fire(new BuildSelectEvent(tile, team, self(), nextPlan.breaking));
                nextPlan.initialized = true;
            }

            //if there is no core to build with or no build entity, stop building!
            if(!(tile.build instanceof ConstructBuild entity)){
                continue;
            }

            float bs = 1f / entity.buildCost * type.buildSpeed * buildSpeedMultiplier * state.rules.buildSpeed(team);

            //otherwise, update it.
            if(nextPlan.breaking){
                entity.deconstruct(self(), core, bs);
            }else if(entity.current != null && (state.isEditor() || (state.rules.waves && team == state.rules.waveTeam && entity.current.isVisible()) || (entity.current.unlockedNowHost() && entity.current.environmentBuildable() && entity.current.isPlaceable()))){ //only allow building unlocked blocks
                entity.construct(self(), core, bs, nextPlan.config);
            }

            nextPlan.stuck = Mathf.equal(nextPlan.progress, entity.progress);
            nextPlan.progress = entity.progress;
        }
    }

    void drawPlan(BuildPlan plan, float alpha){
        plan.animScale = 1f;
        if(plan.breaking){
            control.input.drawBreaking(plan);
        }else{
            plan.block.drawPlan(plan, control.input.allPlans(),
            Build.validPlace(plan.block, team, plan.x, plan.y, plan.rotation) || control.input.planMatches(plan),
            alpha);
        }
    }

    void drawPlanTop(BuildPlan plan, float alpha){
        if(!plan.breaking){
            Draw.reset();
            Draw.mixcol(Color.white, 0.24f + Mathf.absin(Time.globalTime, 6f, 0.28f));
            Draw.alpha(alpha);
            plan.block.drawPlanConfigTop(plan, plans);
        }
    }

    /** @return whether this plan should be skipped, in favor of the next one. */
    boolean shouldSkip(BuildPlan plan, @Nullable Building core){
        if(state.rules.infiniteResources || team.rules().infiniteResources || plan.breaking || core == null || plan.isRotation(team) || plan.isDerelictRepair()) return false;

        return (plan.stuck && !core.items.has(plan.block.requirements)) ||
            (Structs.contains(plan.block.requirements, i -> !core.items.has(i.item, Math.min(i.amount, 15)) && Mathf.round(i.amount * state.rules.buildCostMultiplier) > 0));
    }

    void removeBuild(int x, int y, boolean breaking){
        //remove matching plan
        BuildPlan plan = plans.find((p) ->  p.breaking == breaking && p.x == x && p.y == y);
        if (plan != null)
            clearPlan(plan);
    }

    /** Return whether this builder's place queue contains items. */
    boolean isBuilding(){
        return plans.size != 0;
    }

    /** Clears the placement queue. */
    void clearBuilding(){
        clearAllPlans();
    }

    /** Add another build plans to the queue, if it doesn't exist there yet. */
    void addBuild(BuildPlan place){
        if(!canBuild()) return;

        BuildPlan replace = null;
        for(BuildPlan plan : plans){
            if(plan.x == place.x && plan.y == place.y){
                replace = plan;
                break;
            }
        }
        if(replace != null){
            clearPlan(replace);
        }
        Tile tile = world.tile(place.x, place.y);
        if(tile != null && tile.build instanceof ConstructBuild cons){
            place.progress = cons.progress;
        }
        addPlan(place);
    }

    boolean activelyBuilding(){
        //not actively building when not near the build plan
        if(isBuilding()){
            var plan = buildPlan();
            if(!state.isEditor() && plan != null && !within(plan, state.rules.infiniteResources ? Float.MAX_VALUE : type.buildRange)){
                return false;
            }
        }
        return isBuilding() && updateBuilding;
    }

    /** @return  the build plan currently active, or the one at the top of the queue.*/
    @Nullable BuildPlan buildPlan(){
        return plans.size == 0 ? null : plans.first();
    }

    public void drawBuilding(){
        //TODO make this more generic so it works with builder "weapons"
        boolean active = activelyBuilding();
        if(!active && lastActive == null) return;

        Draw.z(Layer.flyingUnit);

        BuildPlan plan = active ? buildPlan() : lastActive;
        Tile tile = plan.tile();
        var core = team.core();

        if(tile == null || !within(plan, state.rules.infiniteResources ? Float.MAX_VALUE : type.buildRange)){
            return;
        }

        //draw remote plans.
        if(core != null && active && !isLocal() && !(tile.block() instanceof ConstructBlock) && !state.isPaused()){
            Draw.z(Layer.plans - 1f);
            drawPlan(plan, 0.5f);
            drawPlanTop(plan, 0.5f);
            Draw.z(Layer.flyingUnit);
        }

        if(type.drawBuildBeam){
            float focusLen = type.buildBeamOffset + Mathf.absin(Time.time, 3f, 0.6f);
            float px = x + Angles.trnsx(rotation, focusLen);
            float py = y + Angles.trnsy(rotation, focusLen);

            drawBuildingBeam(px, py);
        }
    }

    public void drawBuildingBeam(float px, float py){
        boolean active = activelyBuilding();
        if(!active && lastActive == null) return;

        Draw.z(Layer.flyingUnit);

        BuildPlan plan = active ? buildPlan() : lastActive;
        Tile tile = world.tile(plan.x, plan.y);

        if(tile == null || !within(plan, state.rules.infiniteResources ? Float.MAX_VALUE : type.buildRange)){
            return;
        }

        int size = plan.breaking ? active ? tile.block().size : lastSize : plan.block.size;
        float tx = plan.drawx(), ty = plan.drawy();

        Lines.stroke(1f, plan.breaking ? Pal.remove : Pal.accent);
        Draw.z(Layer.buildBeam);

        Draw.alpha(buildAlpha);

        if(!active && !(tile.build instanceof ConstructBuild)){
            Fill.square(plan.drawx(), plan.drawy(), size * tilesize/2f);
        }

        Drawf.buildBeam(px, py, tx, ty, Vars.tilesize * size / 2f);

        Fill.square(px, py, 1.8f + Mathf.absin(Time.time, 2.2f, 1.1f), rotation + 45);

        Draw.reset();
        Draw.z(Layer.flyingUnit);
    }
}
