package me.desht.pneumaticcraft.common.ai;

import me.desht.pneumaticcraft.common.ai.LogisticsManager.LogisticsTask;
import me.desht.pneumaticcraft.common.progwidgets.ICountWidget;
import me.desht.pneumaticcraft.common.progwidgets.ILiquidFiltered;
import me.desht.pneumaticcraft.common.progwidgets.ISidedWidget;
import me.desht.pneumaticcraft.common.progwidgets.ProgWidgetAreaItemBase;
import me.desht.pneumaticcraft.common.semiblock.ISemiBlock;
import me.desht.pneumaticcraft.common.semiblock.SemiBlockLogistics;
import me.desht.pneumaticcraft.common.semiblock.SemiBlockManager;
import me.desht.pneumaticcraft.common.util.StreamUtils;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Stream;

public class DroneAILogistics extends EntityAIBase {
    private EntityAIBase curAI;
    private final IDroneBase drone;
    private final ProgWidgetAreaItemBase widget;
    private final LogisticsManager manager = new LogisticsManager();
    private LogisticsTask curTask;

    public DroneAILogistics(IDroneBase drone, ProgWidgetAreaItemBase widget) {
        this.drone = drone;
        this.widget = widget;
    }

    @Override
    public boolean shouldExecute() {
        manager.clearLogistics();
        Set<BlockPos> area = widget.getCachedAreaSet();
        if (area.size() == 0) return false;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : area) {
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        AxisAlignedBB aabb = new AxisAlignedBB(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
        Stream<ISemiBlock> semiBlocksInArea = SemiBlockManager.getInstance(drone.world()).getSemiBlocksInArea(drone.world(), aabb);
        Stream<SemiBlockLogistics> logisticFrames = StreamUtils.ofType(SemiBlockLogistics.class, semiBlocksInArea);
        logisticFrames.filter(frame -> area.contains(frame.getPos())).forEach(manager::addLogisticFrame);
        
        curTask = null;
        return doLogistics();
    }

    private boolean doLogistics() {
        ItemStack item = drone.getInv().getStackInSlot(0);
        FluidStack fluid = drone.getTank().getFluid();
        PriorityQueue<LogisticsTask> tasks = manager.getTasks(item.isEmpty() ? fluid : item);
        if (tasks.size() > 0) {
            curTask = tasks.poll();
            return execute(curTask);
        }
        return false;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (curTask == null) return false;
        if (!curAI.shouldContinueExecuting()) {
            if (curAI instanceof DroneEntityAIInventoryImport) {
                curTask.requester.clearIncomingStack(curTask.transportingItem);
                return clearAIAndProvideAgain();
            } else if (curAI instanceof DroneAILiquidImport) {
                curTask.requester.clearIncomingStack(curTask.transportingFluid);
                return clearAIAndProvideAgain();
            } else {
                curAI = null;
                return false;
            }
        } else {
            curTask.informRequester();
            return true;
        }
    }

    private boolean clearAIAndProvideAgain() {
        curAI = null;
        if (curTask.isStillValid(drone.getInv().getStackInSlot(0).isEmpty() ? drone.getTank().getFluid() : drone.getInv().getStackInSlot(0)) && execute(curTask)) {
            return true;
        } else {
            curTask = null;
            return doLogistics();
        }
    }

    public boolean execute(LogisticsTask task) {
        if (!drone.getInv().getStackInSlot(0).isEmpty()) {
            if (!isPosPathfindable(task.requester.getPos())) return false;
            curAI = new DroneEntityAIInventoryExport(drone,
                    new FakeWidgetLogistics(task.requester.getPos(), task.requester.getSide(), task.transportingItem));
        } else if (drone.getTank().getFluidAmount() > 0) {
            if (!isPosPathfindable(task.requester.getPos())) return false;
            curAI = new DroneAILiquidExport(drone,
                    new FakeWidgetLogistics(task.requester.getPos(), task.requester.getSide(), task.transportingFluid.stack));
        } else if (!task.transportingItem.isEmpty()) {
            if (!isPosPathfindable(task.provider.getPos())) return false;
            curAI = new DroneEntityAIInventoryImport(drone,
                    new FakeWidgetLogistics(task.provider.getPos(), task.provider.getSide(), task.transportingItem));
        } else {
            if (!isPosPathfindable(task.provider.getPos())) return false;
            curAI = new DroneAILiquidImport(drone,
                    new FakeWidgetLogistics(task.provider.getPos(),  task.provider.getSide(), task.transportingFluid.stack));
        }
        if (curAI.shouldExecute()) {
            task.informRequester();
            return true;
        } else {
            return false;
        }
    }

    private boolean isPosPathfindable(BlockPos pos) {
        for (EnumFacing d : EnumFacing.VALUES) {
            if (drone.isBlockValidPathfindBlock(pos.offset(d))) return true;
        }
        return false;
    }

    private static class FakeWidgetLogistics extends ProgWidgetAreaItemBase implements ISidedWidget, ICountWidget,
            ILiquidFiltered {
        @Nonnull
        private final ItemStack stack;
        private final FluidStack fluid;
        private final Set<BlockPos> area;
        private final boolean[] sides = new boolean[6];

        FakeWidgetLogistics(BlockPos pos, EnumFacing side, @Nonnull ItemStack stack) {
            this.stack = stack;
            this.fluid = null;
            area = new HashSet<>();
            area.add(pos);
            sides[side.getIndex()] = true;
        }

        FakeWidgetLogistics(BlockPos pos, EnumFacing side, FluidStack fluid) {
            this.stack = ItemStack.EMPTY;
            this.fluid = fluid;
            area = new HashSet<>();
            area.add(pos);
            sides[side.getIndex()] = true;
        }

        @Override
        public String getWidgetString() {
            return null;
        }

        @Override
        public int getCraftingColorIndex() {
            return 0;
        }

        @Override
        public void getArea(Set<BlockPos> area) {
            area.addAll(this.area);
        }

        @Override
        public void setSides(boolean[] sides) {
        }

        @Override
        public boolean[] getSides() {
            return sides;
        }

        @Override
        public boolean isItemValidForFilters(@Nonnull ItemStack item) {
            return !item.isEmpty() && item.isItemEqual(stack);
        }

        @Override
        public ResourceLocation getTexture() {
            return null;
        }

        @Override
        public boolean useCount() {
            return true;
        }

        @Override
        public void setUseCount(boolean useCount) {
        }

        @Override
        public int getCount() {
            return !stack.isEmpty() ? stack.getCount() : fluid.amount;
        }

        @Override
        public void setCount(int count) {
        }

        @Override
        public boolean isFluidValid(Fluid fluid) {
            return fluid == this.fluid.getFluid();
        }

    }

}
