package fi.dy.masa.minihud.renderer.shapes;

import java.util.List;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import fi.dy.masa.malilib.util.*;
import fi.dy.masa.malilib.util.data.Color4f;

public abstract class ShapeCircleBase extends ShapeBlocky
{
    private static final double DEFAULT_MAX_RADIUS = 1024.0;

    protected Direction mainAxis = Direction.UP;
    private double maxRadius = DEFAULT_MAX_RADIUS;
    private double radius;
    private double radiusSq;
    private Vec3d center = Vec3d.ZERO;
    private Vec3d effectiveCenter = Vec3d.ZERO;

    public ShapeCircleBase(ShapeType type, Color4f color, double radius)
    {
        super(type, color);

        this.setRadius(radius);

        Entity entity = EntityUtils.getCameraEntity();

        if (entity != null)
        {
            Vec3d center = entity.getPos();
            center = new Vec3d(Math.floor(center.x) + 0.5, Math.floor(center.y), Math.floor(center.z) + 0.5);
            this.setCenter(center);
        }
        else
        {
            this.setCenter(Vec3d.ZERO);
        }
    }

    public Vec3d getCenter()
    {
        return this.center;
    }

    public Vec3d getEffectiveCenter()
    {
        return this.effectiveCenter;
    }

    public void setCenter(Vec3d center)
    {
        this.center = center;
        this.updateEffectiveCenter();
    }

    @Override
    public void moveToPosition(Vec3d pos)
    {
        this.setCenter(pos);
        InfoUtils.printActionbarMessage(String.format("Moved shape to %.1f %.1f %.1f",
                                                      pos.getX(), pos.getY(), pos.getZ()));
    }

    public double getRadius()
    {
        return this.radius;
    }

    public double getSquaredRadius()
    {
        return this.radiusSq;
    }

    public void setRadius(double radius)
    {
        if (radius >= 0.0 && radius <= this.maxRadius)
        {
            this.radius = radius;
            this.radiusSq = radius * radius;
            this.setRenderPerimeter(this.effectiveCenter, this.radius + 512);
            this.setNeedsUpdate();
        }
    }

    public Direction getMainAxis()
    {
        return this.mainAxis;
    }

    public void setMainAxis(Direction mainAxis)
    {
        this.mainAxis = mainAxis;
        this.setNeedsUpdate();
    }

    protected BlockPos getCenterBlock()
    {
        return BlockPos.ofFloored(this.effectiveCenter);
    }

    @Override
    public void setBlockSnap(BlockSnap snap)
    {
        super.setBlockSnap(snap);
        this.updateEffectiveCenter();
    }

    protected void updateEffectiveCenter()
    {
        this.effectiveCenter = this.getBlockSnappedPosition(this.center);
        //System.out.printf("ShapeCircleBase - updateEffectiveCenter: center: [%s], effectiveCenter [%s] // radius: [%f]\n", this.center.toString(), this.effectiveCenter.toString(), this.radius);
        this.setRenderPerimeter(this.effectiveCenter, this.radius + 512);
        this.setNeedsUpdate();
    }

    @Override
    public JsonObject toJson()
    {
        JsonObject obj = super.toJson();

        obj.add("center", JsonUtils.vec3dToJson(this.center));
        obj.add("main_axis", new JsonPrimitive(this.mainAxis.name()));
        obj.add("radius", new JsonPrimitive(this.getRadius()));

        if (this.maxRadius != DEFAULT_MAX_RADIUS)
        {
            obj.add("max_radius", new JsonPrimitive(this.maxRadius));
        }

        return obj;
    }

    @Override
    public void fromJson(JsonObject obj)
    {
        // Note: The snap value has to be set before the center
        super.fromJson(obj);

        if (JsonUtils.hasString(obj, "main_axis"))
        {
            Direction facing = Direction.valueOf(obj.get("main_axis").getAsString());

            if (facing != null)
            {
                this.setMainAxis(facing);
            }
        }

        if (JsonUtils.hasDouble(obj, "max_radius"))
        {
            double maxRadius = JsonUtils.getDouble(obj, "max_radius");

            if (maxRadius > 0 && maxRadius < 1000000)
            {
                this.maxRadius = maxRadius;
            }
        }

        if (JsonUtils.hasDouble(obj, "radius"))
        {
            this.setRadius(JsonUtils.getDouble(obj, "radius"));
        }

        Vec3d center = JsonUtils.vec3dFromJson(obj, "center");

        if (center != null)
        {
            this.setCenter(center);
        }
    }

    @Override
    public List<String> getWidgetHoverLines()
    {
        List<String> lines = super.getWidgetHoverLines();
        BlockSnap snap = this.getBlockSnap();
        Vec3d c = this.center;

        lines.add(StringUtils.translate("minihud.gui.hover.shape.radius_value", this.getRadius()));
        lines.add(StringUtils.translate("minihud.gui.hover.shape.center_value", d2(c.x), d2(c.y), d2(c.z)));

        if (snap != BlockSnap.NONE)
        {
            c = this.effectiveCenter;
            lines.add(StringUtils.translate("minihud.gui.hover.shape.effective_center_value", d2(c.x), d2(c.y), d2(c.z)));
        }

        return lines;
    }
}
