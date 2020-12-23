package me.fallenbreath.pistorder;

import com.mojang.blaze3d.platform.GlStateManager;
import me.fallenbreath.pistorder.mixins.PistonBlockAccessor;
import me.fallenbreath.pistorder.pushlimit.PushLimitManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.piston.PistonHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.debug.DebugRenderer;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class Pistorder
{
	private static final Pistorder INSTANCE = new Pistorder();
	private static final double MAX_RENDER_DISTANCE = 256.0D;
	private static final int MAX_PUSH_LIMIT_FOR_CALC = 128;
	private static final float FONT_SIZE = 0.025F;

	private ClickInfo info = null;
	private List<BlockPos> movedBlocks;
	private List<BlockPos> brokenBlocks;
	private boolean moveSuccess;

	public static Pistorder getInstance()
	{
		return INSTANCE;
	}

	public ActionResult onPlayerRightClickBlock(World world, PlayerEntity player, Hand hand, BlockHitResult hit)
	{
		// click with empty main hand, not sneaking
		if (hand == Hand.MAIN_HAND && player.getMainHandStack().isEmpty() && !player.isSneaking())
		{
			BlockPos pos = hit.getBlockPos();
			BlockState blockState = world.getBlockState(pos);
			Block block = blockState.getBlock();
			if (block instanceof PistonBlock)
			{
				boolean extended = blockState.get(PistonBlock.EXTENDED);
				if (!extended || ((PistonBlockAccessor)block).getIsSticky())
				{
					this.click(world, pos, blockState, blockState.get(Properties.FACING), extended ? ActionType.RETRACT : ActionType.PUSH);
					return ActionResult.SUCCESS;
				}
			}
		}
		return ActionResult.FAIL;
	}

	public boolean isEnabled()
	{
		return this.info != null;
	}

	private void disable()
	{
		this.info = null;
	}

	synchronized private void click(World world, BlockPos pos, BlockState blockState, Direction pistonFacing, ActionType actionType)
	{
		ClickInfo newInfo = new ClickInfo(world, pos, blockState, pistonFacing, actionType);
		if (newInfo.equals(this.info))
		{
			this.disable();
		}
		else
		{
			this.info = newInfo;

			BlockState[] states = new BlockState[2];
			if (actionType.isRetract())
			{
				states[0] = world.getBlockState(pos);  // piston base
				states[1] = world.getBlockState(pos.offset(pistonFacing));  // piston head
				world.setBlockState(pos, Blocks.AIR.getDefaultState(), 18);
				world.setBlockState(pos.offset(pistonFacing), Blocks.AIR.getDefaultState(), 18);
			}

			PistonHandler pistonHandler = new PistonHandler(world, pos, pistonFacing, actionType.isPush());
			this.moveSuccess = pistonHandler.calculatePush();

			if (!this.moveSuccess)
			{
				PushLimitManager.getInstance().overwritePushLimit(MAX_PUSH_LIMIT_FOR_CALC);
				pistonHandler.calculatePush();
			}

			this.brokenBlocks = pistonHandler.getBrokenBlocks();
			this.movedBlocks = pistonHandler.getMovedBlocks();
			// reverse the list for correct order
			Collections.reverse(this.brokenBlocks);
			Collections.reverse(this.movedBlocks);

			PushLimitManager.getInstance().restorePushLimit();

			if (actionType.isRetract())
			{
				world.setBlockState(pos, states[0], 18);
				world.setBlockState(pos.offset(pistonFacing), states[1], 18);
			}
		}
	}

	/**
	 * Stolen from {@link DebugRenderer#method_3712(String, double, double, double, int, float, boolean, float, boolean)}
	 */
	public static void drawString(String text, BlockPos pos, float tickDelta, int color, float line)
	{
		MinecraftClient client = MinecraftClient.getInstance();
		Camera camera = client.gameRenderer.getCamera();
		if (camera.isReady() && client.getEntityRenderManager().gameOptions != null && client.player != null)
		{
			double x = (double)pos.getX() + 0.5D;
			double y = (double)pos.getY() + 0.5D;
			double z = (double)pos.getZ() + 0.5D;
			if (client.player.squaredDistanceTo(x, y, z) > MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE)
			{
				return;
			}
			double camX = camera.getPos().x;
			double camY = camera.getPos().y;
			double camZ = camera.getPos().z;
			GlStateManager.pushMatrix();
			GlStateManager.translatef((float)(x - camX), (float)(y - camY), (float)(z - camZ));
			GlStateManager.normal3f(0.0F, 1.0F, 0.0F);
			GlStateManager.scalef(FONT_SIZE, -FONT_SIZE, FONT_SIZE);
			EntityRenderDispatcher entityRenderDispatcher = client.getEntityRenderManager();
			GlStateManager.rotatef(-entityRenderDispatcher.cameraYaw, 0.0F, 1.0F, 0.0F);
			GlStateManager.rotatef(-entityRenderDispatcher.cameraPitch, 1.0F, 0.0F, 0.0F);
			GlStateManager.enableTexture();
			GlStateManager.disableDepthTest();  // visibleThroughObjects
			GlStateManager.depthMask(true);
			GlStateManager.scalef(-1.0F, 1.0F, 1.0F);

			float renderX = -client.textRenderer.getStringWidth(text) * 0.5F;
			float renderY = client.textRenderer.getStringBoundedHeight(text, Integer.MAX_VALUE) * (-0.5F + 1.25F * line);
			client.textRenderer.draw(text, renderX, renderY, color);

			GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
			GlStateManager.enableDepthTest();
			GlStateManager.popMatrix();
		}
	}

	private boolean checkState(World world, ClickInfo info)
	{
		if (!Objects.equals(world, info.world))
		{
			return false;
		}
		BlockView chunk = world.getChunkManager().getChunk(info.pos.getX() >> 4, info.pos.getZ() >> 4);
		if (chunk instanceof WorldChunk && !((WorldChunk)chunk).isEmpty())  // it's a real loaded chunk
		{
			return chunk.getBlockState(info.pos).equals(info.blockState);
		}
		return true;
	}

	@SuppressWarnings("ConstantConditions")
	public void render(float tickDelta)
	{
		if (this.isEnabled())
		{
			MinecraftClient client = MinecraftClient.getInstance();
			if (!this.checkState(client.world, this.info))
			{
				this.disable();
				return;
			}

			String actionKey = this.info.actionType.isPush() ? "pistorder.push" : "pistorder.retract";
			String actionResult = this.moveSuccess ? Formatting.GREEN + "√" : Formatting.RED + "×";
			drawString(String.format("%s %s", I18n.translate(actionKey), actionResult), this.info.pos, tickDelta, Formatting.GOLD.getColorValue(), -0.5F);
			drawString(I18n.translate("pistorder.block_count", this.movedBlocks.size()), this.info.pos, tickDelta, Formatting.GOLD.getColorValue(), 0.5F);

			for (int i = 0; i < this.movedBlocks.size(); i++)
			{
				drawString(String.valueOf(i + 1), this.movedBlocks.get(i), tickDelta, Formatting.WHITE.getColorValue(), 0);
			}
			for (int i = 0; i < this.brokenBlocks.size(); i++)
			{
				drawString(String.valueOf(i + 1), this.brokenBlocks.get(i), tickDelta, Formatting.RED.getColorValue() | (0xFF << 24), 0);
			}
		}
	}

	public static class ClickInfo
	{
		public final World world;
		public final BlockPos pos;
		public final BlockState blockState;
		public final Direction direction;
		public final ActionType actionType;

		public ClickInfo(World world, BlockPos pos, BlockState blockState, Direction direction, ActionType actionType)
		{
			this.world = world;
			this.pos = pos;
			this.blockState = blockState;
			this.direction = direction;
			this.actionType = actionType;
		}

		@Override
		public boolean equals(Object o)
		{
			if (this == o) return true;
			if (!(o instanceof ClickInfo)) return false;
			ClickInfo that = (ClickInfo) o;
			return Objects.equals(world, that.world) &&
					Objects.equals(pos, that.pos) &&
					direction == that.direction &&
					actionType == that.actionType;
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(world, pos, direction, actionType);
		}
	}

	public enum ActionType
	{
		PUSH,
		RETRACT;

		public boolean isPush()
		{
			return this == ActionType.PUSH;
		}

		public boolean isRetract()
		{
			return this == ActionType.RETRACT;
		}
	}
}
