// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.gui;

import java.awt.Cursor;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.ProjectionBounds;
import org.openstreetmap.josm.data.SystemOfMeasurement;
import org.openstreetmap.josm.data.ViewportData;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.ILatLon;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.IPrimitive;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.WaySegment;
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor;
import org.openstreetmap.josm.data.preferences.BooleanProperty;
import org.openstreetmap.josm.data.preferences.DoubleProperty;
import org.openstreetmap.josm.data.preferences.IntegerProperty;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.data.projection.ProjectionChangeListener;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.gui.PrimitiveHoverListener.PrimitiveHoverEvent;
import org.openstreetmap.josm.gui.help.Helpful;
import org.openstreetmap.josm.gui.layer.NativeScaleLayer;
import org.openstreetmap.josm.gui.layer.NativeScaleLayer.Scale;
import org.openstreetmap.josm.gui.layer.NativeScaleLayer.ScaleList;
import org.openstreetmap.josm.gui.mappaint.MapPaintStyles;
import org.openstreetmap.josm.gui.mappaint.mapcss.MapCSSStyleSource;
import org.openstreetmap.josm.gui.util.CursorManager;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Utils;
import org.openstreetmap.josm.tools.bugreport.BugReportExceptionHandler;

/**
 * A component that can be navigated by a {@link MapMover}. Used as map view and for the
 * zoomer in the download dialog.
 *
 * @author imi
 * @since 41
 */
public class NavigatableComponent extends JComponent implements Helpful {

    private static final double ALIGNMENT_EPSILON = 1e-3;

    /**
     * Interface to notify listeners of the change of the zoom area.
     * @since 10600 (functional interface)
     */
    @FunctionalInterface
    public interface ZoomChangeListener {
        /**
         * Method called when the zoom area has changed.
         */
        void zoomChanged();
    }

    /**
     * To determine if a primitive is currently selectable.
     */
    public transient Predicate<OsmPrimitive> isSelectablePredicate = prim -> {
        if (!prim.isSelectable()) return false;
        // if it isn't displayed on screen, you cannot click on it
        MapCSSStyleSource.STYLE_SOURCE_LOCK.readLock().lock();
        try {
            return !MapPaintStyles.getStyles().get(prim, getDist100Pixel(), this).isEmpty();
        } finally {
            MapCSSStyleSource.STYLE_SOURCE_LOCK.readLock().unlock();
        }
    };

    /** Snap distance */
    public static final IntegerProperty PROP_SNAP_DISTANCE = new IntegerProperty("mappaint.node.snap-distance", 10);
    /** Zoom steps to get double scale */
    public static final DoubleProperty PROP_ZOOM_RATIO = new DoubleProperty("zoom.ratio", 2.0);
    /** Divide intervals between native resolution levels to smaller steps if they are much larger than zoom ratio */
    public static final BooleanProperty PROP_ZOOM_INTERMEDIATE_STEPS = new BooleanProperty("zoom.intermediate-steps", true);
    /** scale follows native resolution of layer status when layer is created */
    public static final BooleanProperty PROP_ZOOM_SCALE_FOLLOW_NATIVE_RES_AT_LOAD = new BooleanProperty(
            "zoom.scale-follow-native-resolution-at-load", true);

    /**
     * The layer which scale is set to.
     */
    private transient NativeScaleLayer nativeScaleLayer;

    /**
     * the zoom listeners
     */
    private static final CopyOnWriteArrayList<ZoomChangeListener> zoomChangeListeners = new CopyOnWriteArrayList<>();

    /**
     * Removes a zoom change listener
     *
     * @param listener the listener. Ignored if null or already absent
     */
    public static void removeZoomChangeListener(ZoomChangeListener listener) {
        zoomChangeListeners.remove(listener);
    }

    /**
     * Adds a zoom change listener
     *
     * @param listener the listener. Ignored if null or already registered.
     */
    public static void addZoomChangeListener(ZoomChangeListener listener) {
        if (listener != null) {
            zoomChangeListeners.addIfAbsent(listener);
        }
    }

    protected static void fireZoomChanged() {
        GuiHelper.runInEDTAndWait(() -> {
            for (ZoomChangeListener l : zoomChangeListeners) {
                l.zoomChanged();
            }
        });
    }

    private final CopyOnWriteArrayList<PrimitiveHoverListener> primitiveHoverListeners = new CopyOnWriteArrayList<>();
    private IPrimitive previousHoveredPrimitive;
    private final PrimitiveHoverMouseListener primitiveHoverMouseListenerHelper = new PrimitiveHoverMouseListener();

    /**
     * Removes a primitive hover listener
     *
     * @param listener the listener. Ignored if null or already absent.
     * @since 18574
     */
    public void removePrimitiveHoverListener(PrimitiveHoverListener listener) {
        primitiveHoverListeners.remove(listener);
    }

    /**
     * Adds a primitive hover listener
     *
     * @param listener the listener. Ignored if null or already registered.
     * @since 18574
     */
    public void addPrimitiveHoverListener(PrimitiveHoverListener listener) {
        if (listener != null) {
            primitiveHoverListeners.addIfAbsent(listener);
        }
    }

    /**
     * Send a {@link PrimitiveHoverEvent} to registered {@link PrimitiveHoverListener}s
     * @param e primitive hover event information
     * @since 18574
     */
    protected void firePrimitiveHovered(PrimitiveHoverEvent e) {
        GuiHelper.runInEDT(() -> {
            for (PrimitiveHoverListener l : primitiveHoverListeners) {
                try {
                    l.primitiveHovered(e);
                } catch (RuntimeException ex) {
                    Logging.logWithStackTrace(Logging.LEVEL_ERROR, "Error in primitive hover listener", ex);
                    BugReportExceptionHandler.handleException(ex);
                }
            }
        });
    }

    private void updateHoveredPrimitive(IPrimitive hovered, MouseEvent e) {
        if (!Objects.equals(hovered, previousHoveredPrimitive)) {
            firePrimitiveHovered(new PrimitiveHoverEvent(hovered, previousHoveredPrimitive, e));
            previousHoveredPrimitive = hovered;
        }
    }

    // The only events that may move/resize this map view are window movements or changes to the map view size.
    // We can clean this up more by only recalculating the state on repaint.
    private final transient HierarchyListener hierarchyListener = e -> {
        long interestingFlags = HierarchyEvent.ANCESTOR_MOVED | HierarchyEvent.SHOWING_CHANGED;
        if ((e.getChangeFlags() & interestingFlags) != 0) {
            updateLocationState();
        }
    };

    private final transient ComponentAdapter componentListener = new ComponentAdapter() {
        @Override
        public void componentShown(ComponentEvent e) {
            updateLocationState();
        }

        @Override
        public void componentResized(ComponentEvent e) {
            updateLocationState();
        }
    };

    protected transient ViewportData initialViewport;

    protected final transient CursorManager cursorManager = new CursorManager(this);

    /**
     * The current state (scale, center, ...) of this map view.
     */
    private transient MapViewState state;

    /**
     * Main uses weak link to store this, so we need to keep a reference.
     */
    private final ProjectionChangeListener projectionChangeListener = (oldValue, newValue) -> fixProjection();

    /**
     * Constructs a new {@code NavigatableComponent}.
     */
    public NavigatableComponent() {
        setLayout(null);
        state = MapViewState.createDefaultState(getWidth(), getHeight());
        ProjectionRegistry.addProjectionChangeListener(projectionChangeListener);
    }

    @Override
    public void addNotify() {
        updateLocationState();
        addHierarchyListener(hierarchyListener);
        addComponentListener(componentListener);
        addPrimitiveHoverMouseListeners();
        super.addNotify();
    }

    @Override
    public void removeNotify() {
        removeHierarchyListener(hierarchyListener);
        removeComponentListener(componentListener);
        removePrimitiveHoverMouseListeners();
        super.removeNotify();
    }

    private void addPrimitiveHoverMouseListeners() {
        addMouseMotionListener(primitiveHoverMouseListenerHelper);
        addMouseListener(primitiveHoverMouseListenerHelper);
    }

    private void removePrimitiveHoverMouseListeners() {
        removeMouseMotionListener(primitiveHoverMouseListenerHelper);
        removeMouseListener(primitiveHoverMouseListenerHelper);
    }

    /**
     * Choose a layer that scale will be snap to its native scales.
     * @param nativeScaleLayer layer to which scale will be snapped
     */
    public void setNativeScaleLayer(NativeScaleLayer nativeScaleLayer) {
        this.nativeScaleLayer = nativeScaleLayer;
        zoomTo(getCenter(), scaleRound(getScale()));
        repaint();
    }

    /**
     * Replies the layer which scale is set to.
     * @return the current scale layer (may be null)
     */
    public NativeScaleLayer getNativeScaleLayer() {
        return nativeScaleLayer;
    }

    /**
     * Get a new scale that is zoomed in from previous scale
     * and snapped to selected native scale layer.
     * @return new scale
     */
    public double scaleZoomIn() {
        return scaleZoomManyTimes(-1);
    }

    /**
     * Get a new scale that is zoomed out from previous scale
     * and snapped to selected native scale layer.
     * @return new scale
     */
    public double scaleZoomOut() {
        return scaleZoomManyTimes(1);
    }

    /**
     * Get a new scale that is zoomed in/out a number of times
     * from previous scale and snapped to selected native scale layer.
     * @param times count of zoom operations, negative means zoom in
     * @return new scale
     */
    public double scaleZoomManyTimes(int times) {
        if (nativeScaleLayer != null) {
            ScaleList scaleList = nativeScaleLayer.getNativeScales();
            if (scaleList != null) {
                if (PROP_ZOOM_INTERMEDIATE_STEPS.get()) {
                    scaleList = scaleList.withIntermediateSteps(PROP_ZOOM_RATIO.get());
                }
                Scale s = scaleList.scaleZoomTimes(getScale(), PROP_ZOOM_RATIO.get(), times);
                return s != null ? s.getScale() : 0;
            }
        }
        return getScale() * Math.pow(PROP_ZOOM_RATIO.get(), times);
    }

    /**
     * Get a scale snapped to native resolutions, use round method.
     * It gives nearest step from scale list.
     * Use round method.
     * @param scale to snap
     * @return snapped scale
     */
    public double scaleRound(double scale) {
        return scaleSnap(scale, false);
    }

    /**
     * Get a scale snapped to native resolutions.
     * It gives nearest lower step from scale list, usable to fit objects.
     * @param scale to snap
     * @return snapped scale
     */
    public double scaleFloor(double scale) {
        return scaleSnap(scale, true);
    }

    /**
     * Get a scale snapped to native resolutions.
     * It gives nearest lower step from scale list, usable to fit objects.
     * @param scale to snap
     * @param floor use floor instead of round, set true when fitting view to objects
     * @return new scale
     */
    public double scaleSnap(double scale, boolean floor) {
        if (nativeScaleLayer != null) {
            ScaleList scaleList = nativeScaleLayer.getNativeScales();
            if (scaleList != null) {
                if (PROP_ZOOM_INTERMEDIATE_STEPS.get()) {
                    scaleList = scaleList.withIntermediateSteps(PROP_ZOOM_RATIO.get());
                }
                Scale snapscale = scaleList.getSnapScale(scale, PROP_ZOOM_RATIO.get(), floor);
                return snapscale != null ? snapscale.getScale() : scale;
            }
        }
        return scale;
    }

    /**
     * Zoom in current view. Use configured zoom step and scaling settings.
     */
    public void zoomIn() {
        zoomTo(state.getCenter().getEastNorth(), scaleZoomIn());
    }

    /**
     * Zoom out current view. Use configured zoom step and scaling settings.
     */
    public void zoomOut() {
        zoomTo(state.getCenter().getEastNorth(), scaleZoomOut());
    }

    protected void updateLocationState() {
        if (isVisibleOnScreen()) {
            state = state.usingLocation(this);
        }
    }

    protected boolean isVisibleOnScreen() {
        return SwingUtilities.getWindowAncestor(this) != null && isShowing();
    }

    /**
     * Changes the projection settings used for this map view.
     * <p>
     * Made public temporarily, will be made private later.
     */
    public void fixProjection() {
        state = state.usingProjection(ProjectionRegistry.getProjection());
        repaint();
    }

    /**
     * Gets the current view state. This includes the scale, the current view area and the position.
     * @return The current state.
     */
    public MapViewState getState() {
        return state;
    }

    /**
     * Returns the text describing the given distance in the current system of measurement.
     * @param dist The distance in metres.
     * @return the text describing the given distance in the current system of measurement.
     * @since 3406
     */
    public static String getDistText(double dist) {
        return SystemOfMeasurement.getSystemOfMeasurement().getDistText(dist);
    }

    /**
     * Returns the text describing the given distance in the current system of measurement.
     * @param dist The distance in metres
     * @param format A {@link NumberFormat} to format the area value
     * @param threshold Values lower than this {@code threshold} are displayed as {@code "< [threshold]"}
     * @return the text describing the given distance in the current system of measurement.
     * @since 7135
     */
    public static String getDistText(final double dist, final NumberFormat format, final double threshold) {
        return SystemOfMeasurement.getSystemOfMeasurement().getDistText(dist, format, threshold);
    }

    /**
     * Returns the text describing the distance in meter that correspond to 100 px on screen.
     * @return the text describing the distance in meter that correspond to 100 px on screen
     */
    public String getDist100PixelText() {
        return getDistText(getDist100Pixel());
    }

    /**
     * Get the distance in meter that correspond to 100 px on screen.
     *
     * @return the distance in meter that correspond to 100 px on screen
     */
    public double getDist100Pixel() {
        return getDist100Pixel(true);
    }

    /**
     * Get the distance in meter that correspond to 100 px on screen.
     *
     * @param alwaysPositive if true, makes sure the return value is always
     * &gt; 0. (Two points 100 px apart can appear to be identical if the user
     * has zoomed out a lot and the projection code does something funny.)
     * @return the distance in meter that correspond to 100 px on screen
     */
    public double getDist100Pixel(boolean alwaysPositive) {
        int w = getWidth()/2;
        int h = getHeight()/2;
        ILatLon ll1 = getLatLon(w-50, h);
        ILatLon ll2 = getLatLon(w+50, h);
        double gcd = ll1.greatCircleDistance(ll2);
        if (alwaysPositive && gcd <= 0)
            return 0.1;
        return gcd;
    }

    /**
     * Returns the current center of the viewport.
     *
     * (Use {@link #zoomTo(EastNorth)} to the change the center.)
     *
     * @return the current center of the viewport
     */
    public EastNorth getCenter() {
        return state.getCenter().getEastNorth();
    }

    /**
     * Returns the current scale.
     *
     * In east/north units per pixel.
     *
     * @return the current scale
     */
    public double getScale() {
        return state.getScale();
    }

    /**
     * Returns geographic coordinates from a specific pixel coordination on the screen.
     * @param x X-Pixelposition to get coordinate from
     * @param y Y-Pixelposition to get coordinate from
     *
     * @return Geographic coordinates from a specific pixel coordination on the screen.
     */
    public EastNorth getEastNorth(int x, int y) {
        return state.getForView(x, y).getEastNorth();
    }

    /**
     * Determines the projection bounds of view area.
     * @return the projection bounds of view area
     */
    public ProjectionBounds getProjectionBounds() {
        return getState().getViewArea().getProjectionBounds();
    }

    /* FIXME: replace with better method - used by MapSlider */
    public ProjectionBounds getMaxProjectionBounds() {
        Bounds b = getProjection().getWorldBoundsLatLon();
        return new ProjectionBounds(getProjection().latlon2eastNorth(b.getMin()),
                getProjection().latlon2eastNorth(b.getMax()));
    }

    /* FIXME: replace with better method - used by Main to reset Bounds when projection changes, don't use otherwise */
    public Bounds getRealBounds() {
        return getState().getViewArea().getCornerBounds();
    }

    /**
     * Returns unprojected geographic coordinates for a specific pixel position on the screen.
     * @param x X-Pixelposition to get coordinate from
     * @param y Y-Pixelposition to get coordinate from
     *
     * @return Geographic unprojected coordinates from a specific pixel position on the screen.
     */
    public LatLon getLatLon(int x, int y) {
        return getProjection().eastNorth2latlon(getEastNorth(x, y));
    }

    /**
     * Returns unprojected geographic coordinates for a specific pixel position on the screen.
     * @param x X-Pixelposition to get coordinate from
     * @param y Y-Pixelposition to get coordinate from
     *
     * @return Geographic unprojected coordinates from a specific pixel position on the screen.
     */
    public LatLon getLatLon(double x, double y) {
        return getLatLon((int) x, (int) y);
    }

    /**
     * Determines the projection bounds of given rectangle.
     * @param r rectangle
     * @return the projection bounds of {@code r}
     */
    public ProjectionBounds getProjectionBounds(Rectangle r) {
        return getState().getViewArea(r).getProjectionBounds();
    }

    /**
     * Returns minimum bounds that will cover a given rectangle.
     * @param r rectangle
     * @return Minimum bounds that will cover rectangle
     */
    public Bounds getLatLonBounds(Rectangle r) {
        return ProjectionRegistry.getProjection().getLatLonBoundsBox(getProjectionBounds(r));
    }

    /**
     * Creates an affine transform that is used to convert the east/north coordinates to view coordinates.
     * @return The affine transform.
     */
    public AffineTransform getAffineTransform() {
        return getState().getAffineTransform();
    }

    /**
     * Return the point on the screen where this Coordinate would be.
     * @param p The point, where this geopoint would be drawn.
     * @return The point on screen where "point" would be drawn, relative to the own top/left.
     */
    public Point2D getPoint2D(EastNorth p) {
        if (null == p)
            return new Point();
        return getState().getPointFor(p).getInView();
    }

    /**
     * Return the point on the screen where this Coordinate would be.
     *
     * Alternative: {@link #getState()}, then {@link MapViewState#getPointFor(ILatLon)}
     * @param latlon The point, where this geopoint would be drawn.
     * @return The point on screen where "point" would be drawn, relative to the own top/left.
     */
    public Point2D getPoint2D(ILatLon latlon) {
        if (latlon == null) {
            return new Point();
        } else {
            return getPoint2D(latlon.getEastNorth(ProjectionRegistry.getProjection()));
        }
    }

    /**
     * Return the point on the screen where this Coordinate would be.
     *
     * Alternative: {@link #getState()}, then {@link MapViewState#getPointFor(ILatLon)}
     * @param latlon The point, where this geopoint would be drawn.
     * @return The point on screen where "point" would be drawn, relative to the own top/left.
     */
    public Point2D getPoint2D(LatLon latlon) {
        return getPoint2D((ILatLon) latlon);
    }

    /**
     * Return the point on the screen where this Node would be.
     *
     * Alternative: {@link #getState()}, then {@link MapViewState#getPointFor(ILatLon)}
     * @param n The node, where this geopoint would be drawn.
     * @return The point on screen where "node" would be drawn, relative to the own top/left.
     */
    public Point2D getPoint2D(Node n) {
        return getPoint2D(n.getEastNorth());
    }

    /**
     * looses precision, may overflow (depends on p and current scale)
     * @param p east/north
     * @return point
     * @see #getPoint2D(EastNorth)
     */
    public Point getPoint(EastNorth p) {
        Point2D d = getPoint2D(p);
        return new Point((int) d.getX(), (int) d.getY());
    }

    /**
     * looses precision, may overflow (depends on p and current scale)
     * @param latlon lat/lon
     * @return point
     * @see #getPoint2D(LatLon)
     * @since 12725
     */
    public Point getPoint(ILatLon latlon) {
        Point2D d = getPoint2D(latlon);
        return new Point((int) d.getX(), (int) d.getY());
    }

    /**
     * looses precision, may overflow (depends on p and current scale)
     * @param latlon lat/lon
     * @return point
     * @see #getPoint2D(LatLon)
     */
    public Point getPoint(LatLon latlon) {
        return getPoint((ILatLon) latlon);
    }

    /**
     * looses precision, may overflow (depends on p and current scale)
     * @param n node
     * @return point
     * @see #getPoint2D(Node)
     */
    public Point getPoint(Node n) {
        Point2D d = getPoint2D(n);
        return new Point((int) d.getX(), (int) d.getY());
    }

    /**
     * Zoom to the given coordinate and scale.
     *
     * @param newCenter The center x-value (easting) to zoom to.
     * @param newScale The scale to use.
     */
    public void zoomTo(EastNorth newCenter, double newScale) {
        zoomTo(newCenter, newScale, false);
    }

    /**
     * Zoom to the given coordinate and scale.
     *
     * @param center The center x-value (easting) to zoom to.
     * @param scale The scale to use.
     * @param initial true if this call initializes the viewport.
     */
    public void zoomTo(EastNorth center, double scale, boolean initial) {
        Bounds b = getProjection().getWorldBoundsLatLon();
        ProjectionBounds pb = getProjection().getWorldBoundsBoxEastNorth();
        double newScale = scale;
        int width = getWidth();
        int height = getHeight();

        // make sure, the center of the screen is within projection bounds
        double east = center.east();
        double north = center.north();
        east = Math.max(east, pb.minEast);
        east = Math.min(east, pb.maxEast);
        north = Math.max(north, pb.minNorth);
        north = Math.min(north, pb.maxNorth);
        EastNorth newCenter = new EastNorth(east, north);

        // don't zoom out too much, the world bounds should be at least
        // half the size of the screen
        double pbHeight = pb.maxNorth - pb.minNorth;
        if (height > 0 && 2 * pbHeight < height * newScale) {
            double newScaleH = 2 * pbHeight / height;
            double pbWidth = pb.maxEast - pb.minEast;
            if (width > 0 && 2 * pbWidth < width * newScale) {
                double newScaleW = 2 * pbWidth / width;
                newScale = Math.max(newScaleH, newScaleW);
            }
        }

        // don't zoom in too much, minimum: 100 px = 1 cm
        LatLon ll1 = getLatLon(width / 2 - 50, height / 2);
        LatLon ll2 = getLatLon(width / 2 + 50, height / 2);
        if (ll1.isValid() && ll2.isValid() && b.contains(ll1) && b.contains(ll2)) {
            double dm = ll1.greatCircleDistance((ILatLon) ll2);
            double den = 100 * getScale();
            double scaleMin = 0.01 * den / dm / 100;
            if (newScale < scaleMin && !Double.isInfinite(scaleMin)) {
                newScale = scaleMin;
            }
        }

        // snap scale to imagery if needed
        newScale = scaleRound(newScale);

        // Align to the pixel grid:
        // This is a sub-pixel correction to ensure consistent drawing at a certain scale.
        // For example take 2 nodes, that have a distance of exactly 2.6 pixels.
        // Depending on the offset, the distance in rounded or truncated integer
        // pixels will be 2 or 3. It is preferable to have a consistent distance
        // and not switch back and forth as the viewport moves. This can be achieved by
        // locking an arbitrary point to integer pixel coordinates. (Here the EastNorth
        // origin is used as reference point.)
        // Note that the normal right mouse button drag moves the map by integer pixel
        // values, so it is not an issue in this case. It only shows when zooming
        // in & back out, etc.
        MapViewState mvs = getState().usingScale(newScale);
        mvs = mvs.movedTo(mvs.getCenter(), newCenter);
        Point2D enOrigin = mvs.getPointFor(new EastNorth(0, 0)).getInView();
        // as a result of the alignment, it is common to round "half integer" values
        // like 1.49999, which is numerically unstable; add small epsilon to resolve this
        Point2D enOriginAligned = new Point2D.Double(
                Math.round(enOrigin.getX()) + ALIGNMENT_EPSILON,
                Math.round(enOrigin.getY()) + ALIGNMENT_EPSILON);
        EastNorth enShift = mvs.getForView(enOriginAligned.getX(), enOriginAligned.getY()).getEastNorth();
        newCenter = newCenter.subtract(enShift);

        EastNorth oldCenter = getCenter();
        if (!newCenter.equals(oldCenter) || !Utils.equalsEpsilon(getScale(), newScale)) {
            if (!initial) {
                pushZoomUndo(oldCenter, getScale());
            }
            zoomNoUndoTo(newCenter, newScale, initial);
        }
    }

    /**
     * Zoom to the given coordinate without adding to the zoom undo buffer.
     *
     * @param newCenter The center x-value (easting) to zoom to.
     * @param newScale The scale to use.
     * @param initial true if this call initializes the viewport.
     */
    private void zoomNoUndoTo(EastNorth newCenter, double newScale, boolean initial) {
        if (!Utils.equalsEpsilon(getScale(), newScale)) {
            state = state.usingScale(newScale);
        }
        if (!newCenter.equals(getCenter())) {
            state = state.movedTo(state.getCenter(), newCenter);
        }
        if (!initial) {
            repaint();
            fireZoomChanged();
        }
    }

    /**
     * Zoom to given east/north.
     * @param newCenter new center coordinates
     */
    public void zoomTo(EastNorth newCenter) {
        zoomTo(newCenter, getScale());
    }

    /**
     * Zoom to given lat/lon.
     * @param newCenter new center coordinates
     * @since 12725
     */
    public void zoomTo(ILatLon newCenter) {
        zoomTo(getProjection().latlon2eastNorth(newCenter));
    }

    /**
     * Zoom to given lat/lon.
     * @param newCenter new center coordinates
     */
    public void zoomTo(LatLon newCenter) {
        zoomTo((ILatLon) newCenter);
    }

    /**
     * Thread class for smooth scrolling. Made a separate class, so we can safely terminate it.
     */
    private class SmoothScrollThread extends Thread {
        private boolean doStop;
        private final EastNorth oldCenter = getCenter();
        private final EastNorth finalNewCenter;
        private final long frames;
        private final long sleepTime;

        SmoothScrollThread(EastNorth newCenter, long frameNum, int fps) {
            super("smooth-scroller");
            finalNewCenter = newCenter;
            frames = frameNum;
            sleepTime = 1000L / fps;
        }

        @Override
        public void run() {
            try {
                for (int i = 0; i < frames && !doStop; i++) {
                    final EastNorth z = oldCenter.interpolate(finalNewCenter, (1.0+i) / frames);
                    GuiHelper.runInEDTAndWait(() -> {
                        zoomTo(z);
                    });
                    Thread.sleep(sleepTime);
                }
            } catch (InterruptedException ex) {
                Logging.warn("Interruption during smooth scrolling");
            }
        }

        public void stopIt() {
            doStop = true;
        }
    }

    /**
     * Create a thread that moves the viewport to the given center in an animated fashion.
     * @param newCenter new east/north center
     */
    public void smoothScrollTo(EastNorth newCenter) {
        final EastNorth oldCenter = getCenter();
        if (!newCenter.equals(oldCenter)) {
            final int fps = Config.getPref().getInt("smooth.scroll.fps", 20);     // animation frames per second
            final int speed = Config.getPref().getInt("smooth.scroll.speed", 1500); // milliseconds for full-screen-width pan
            final int maxtime = Config.getPref().getInt("smooth.scroll.maxtime", 5000); // milliseconds maximum scroll time
            final double distance = newCenter.distance(oldCenter) / getScale();
            double milliseconds = distance / getWidth() * speed;
            if (milliseconds > maxtime) { // prevent overlong scroll time, speed up if necessary
                milliseconds = maxtime;
            }

            ThreadGroup group = Thread.currentThread().getThreadGroup();
            Thread[] threads = new Thread[group.activeCount()];
            group.enumerate(threads, true);
            boolean stopped = false;
            for (Thread t : threads) {
                if (t instanceof SmoothScrollThread) {
                    ((SmoothScrollThread) t).stopIt();
                    /* handle this case outside in case there is more than one smooth thread */
                    stopped = true;
                }
            }
            if (stopped && milliseconds > maxtime/2.0) { /* we aren't fast enough, skip smooth */
                Logging.warn("Skip smooth scrolling");
                zoomTo(newCenter);
            } else {
                long frames = Math.round(milliseconds * fps / 1000);
                if (frames <= 1)
                    zoomTo(newCenter);
                else
                    new SmoothScrollThread(newCenter, frames, fps).start();
            }
        }
    }

    public void zoomManyTimes(double x, double y, int times) {
        double oldScale = getScale();
        double newScale = scaleZoomManyTimes(times);
        zoomToFactor(x, y, newScale / oldScale);
    }

    public void zoomToFactor(double x, double y, double factor) {
        double newScale = getScale()*factor;
        EastNorth oldUnderMouse = getState().getForView(x, y).getEastNorth();
        MapViewState newState = getState().usingScale(newScale);
        newState = newState.movedTo(newState.getForView(x, y), oldUnderMouse);
        zoomTo(newState.getCenter().getEastNorth(), newScale);
    }

    public void zoomToFactor(EastNorth newCenter, double factor) {
        zoomTo(newCenter, getScale()*factor);
    }

    public void zoomToFactor(double factor) {
        zoomTo(getCenter(), getScale()*factor);
    }

    /**
     * Zoom to given projection bounds.
     * @param box new projection bounds
     */
    public void zoomTo(ProjectionBounds box) {
        double newScale = box.getScale(getWidth(), getHeight());
        newScale = scaleFloor(newScale);
        zoomTo(box.getCenter(), newScale);
    }

    /**
     * Zoom to given bounds.
     * @param box new bounds
     */
    public void zoomTo(Bounds box) {
        zoomTo(new ProjectionBounds(getProjection().latlon2eastNorth(box.getMin()),
                getProjection().latlon2eastNorth(box.getMax())));
    }

    /**
     * Zoom to given viewport data.
     * @param viewport new viewport data
     */
    public void zoomTo(ViewportData viewport) {
        if (viewport == null) return;
        if (viewport.getBounds() != null) {
            if (!viewport.getBounds().hasExtend()) {
                // see #18623
                BoundingXYVisitor v = new BoundingXYVisitor();
                v.visit(viewport.getBounds());
                zoomTo(v);
            } else {
                zoomTo(viewport.getBounds());
            }

        } else {
            zoomTo(viewport.getCenter(), viewport.getScale(), true);
        }
    }

    /**
     * Set the new dimension to the view.
     * @param v box to zoom to
     */
    public void zoomTo(BoundingXYVisitor v) {
        if (v == null) {
            v = new BoundingXYVisitor();
        }
        if (v.getBounds() == null) {
            v.visit(getProjection().getWorldBoundsLatLon());
        }

        // increase bbox. This is required
        // especially if the bbox contains one single node, but helpful
        // in most other cases as well.
        // Do not zoom if the current scale covers the selection, #16706
        final MapView mapView = MainApplication.getMap().mapView;
        final double mapScale = mapView.getScale();
        final double minScale = v.getBounds().getScale(mapView.getWidth(), mapView.getHeight());
        v.enlargeBoundingBoxLogarithmically();
        final double maxScale = v.getBounds().getScale(mapView.getWidth(), mapView.getHeight());
        if (minScale <= mapScale && mapScale < maxScale) {
            mapView.zoomTo(v.getBounds().getCenter());
        } else {
            zoomTo(v.getBounds());
        }
    }

    private static class ZoomData {
        private final EastNorth center;
        private final double scale;

        ZoomData(EastNorth center, double scale) {
            this.center = center;
            this.scale = scale;
        }

        public EastNorth getCenterEastNorth() {
            return center;
        }

        public double getScale() {
            return scale;
        }
    }

    private final transient Stack<ZoomData> zoomUndoBuffer = new Stack<>();
    private final transient Stack<ZoomData> zoomRedoBuffer = new Stack<>();
    private long zoomTimestamp = System.currentTimeMillis();

    private void pushZoomUndo(EastNorth center, double scale) {
        long now = System.currentTimeMillis();
        if ((now - zoomTimestamp) > (Config.getPref().getDouble("zoom.undo.delay", 1.0) * 1000)) {
            zoomUndoBuffer.push(new ZoomData(center, scale));
            if (zoomUndoBuffer.size() > Config.getPref().getInt("zoom.undo.max", 50)) {
                zoomUndoBuffer.remove(0);
            }
            zoomRedoBuffer.clear();
        }
        zoomTimestamp = now;
    }

    /**
     * Zoom to previous location.
     */
    public void zoomPrevious() {
        if (!zoomUndoBuffer.isEmpty()) {
            ZoomData zoom = zoomUndoBuffer.pop();
            zoomRedoBuffer.push(new ZoomData(getCenter(), getScale()));
            zoomNoUndoTo(zoom.getCenterEastNorth(), zoom.getScale(), false);
        }
    }

    /**
     * Zoom to next location.
     */
    public void zoomNext() {
        if (!zoomRedoBuffer.isEmpty()) {
            ZoomData zoom = zoomRedoBuffer.pop();
            zoomUndoBuffer.push(new ZoomData(getCenter(), getScale()));
            zoomNoUndoTo(zoom.getCenterEastNorth(), zoom.getScale(), false);
        }
    }

    /**
     * Determines if zoom history contains "undo" entries.
     * @return {@code true} if zoom history contains "undo" entries
     */
    public boolean hasZoomUndoEntries() {
        return !zoomUndoBuffer.isEmpty();
    }

    /**
     * Determines if zoom history contains "redo" entries.
     * @return {@code true} if zoom history contains "redo" entries
     */
    public boolean hasZoomRedoEntries() {
        return !zoomRedoBuffer.isEmpty();
    }

    private BBox getBBox(Point p, int snapDistance) {
        return new BBox(getLatLon(p.x - snapDistance, p.y - snapDistance),
                getLatLon(p.x + snapDistance, p.y + snapDistance));
    }

    /**
     * The *result* does not depend on the current map selection state, neither does the result *order*.
     * It solely depends on the distance to point p.
     * @param p point
     * @param predicate predicate to match
     *
     * @return a sorted map with the keys representing the distance of their associated nodes to point p.
     */
    private Map<Double, List<Node>> getNearestNodesImpl(Point p, Predicate<OsmPrimitive> predicate) {
        Map<Double, List<Node>> nearestMap = new TreeMap<>();
        DataSet ds = MainApplication.getLayerManager().getActiveDataSet();

        if (ds != null) {
            double dist, snapDistanceSq = PROP_SNAP_DISTANCE.get();
            snapDistanceSq *= snapDistanceSq;

            for (Node n : ds.searchNodes(getBBox(p, PROP_SNAP_DISTANCE.get()))) {
                if (predicate.test(n)
                        && (dist = getPoint2D(n).distanceSq(p)) < snapDistanceSq) {
                    nearestMap.computeIfAbsent(dist, k -> new LinkedList<>()).add(n);
                }
            }
        }

        return nearestMap;
    }

    /**
     * The *result* does not depend on the current map selection state,
     * neither does the result *order*.
     * It solely depends on the distance to point p.
     *
     * @param p the point for which to search the nearest segment.
     * @param ignore a collection of nodes which are not to be returned.
     * @param predicate the returned objects have to fulfill certain properties.
     *
     * @return All nodes nearest to point p that are in a belt from
     *      dist(nearest) to dist(nearest)+4px around p and
     *      that are not in ignore.
     */
    public final List<Node> getNearestNodes(Point p,
            Collection<Node> ignore, Predicate<OsmPrimitive> predicate) {
        List<Node> nearestList = Collections.emptyList();

        if (ignore == null) {
            ignore = Collections.emptySet();
        }

        Map<Double, List<Node>> nlists = getNearestNodesImpl(p, predicate);
        if (!nlists.isEmpty()) {
            Double minDistSq = null;
            for (Entry<Double, List<Node>> entry : nlists.entrySet()) {
                Double distSq = entry.getKey();
                List<Node> nlist = entry.getValue();

                // filter nodes to be ignored before determining minDistSq..
                nlist.removeAll(ignore);
                if (minDistSq == null) {
                    if (!nlist.isEmpty()) {
                        minDistSq = distSq;
                        nearestList = new ArrayList<>();
                        nearestList.addAll(nlist);
                    }
                } else {
                    if (distSq-minDistSq < 16) {
                        nearestList.addAll(nlist);
                    }
                }
            }
        }

        return nearestList;
    }

    /**
     * The *result* does not depend on the current map selection state,
     * neither does the result *order*.
     * It solely depends on the distance to point p.
     *
     * @param p the point for which to search the nearest segment.
     * @param predicate the returned objects have to fulfill certain properties.
     *
     * @return All nodes nearest to point p that are in a belt from
     *      dist(nearest) to dist(nearest)+4px around p.
     * @see #getNearestNodes(Point, Collection, Predicate)
     */
    public final List<Node> getNearestNodes(Point p, Predicate<OsmPrimitive> predicate) {
        return getNearestNodes(p, null, predicate);
    }

    /**
     * The *result* depends on the current map selection state IF use_selected is true.
     *
     * If more than one node within node.snap-distance pixels is found,
     * the nearest node selected is returned IF use_selected is true.
     *
     * Else the nearest new/id=0 node within about the same distance
     * as the true nearest node is returned.
     *
     * If no such node is found either, the true nearest node to p is returned.
     *
     * Finally, if a node is not found at all, null is returned.
     *
     * @param p the screen point
     * @param predicate this parameter imposes a condition on the returned object, e.g.
     *        give the nearest node that is tagged.
     * @param useSelected make search depend on selection
     *
     * @return A node within snap-distance to point p, that is chosen by the algorithm described.
     */
    public final Node getNearestNode(Point p, Predicate<OsmPrimitive> predicate, boolean useSelected) {
        return getNearestNode(p, predicate, useSelected, null);
    }

    /**
     * The *result* depends on the current map selection state IF use_selected is true
     *
     * If more than one node within node.snap-distance pixels is found,
     * the nearest node selected is returned IF use_selected is true.
     *
     * If there are no selected nodes near that point, the node that is related to some of the preferredRefs
     *
     * Else the nearest new/id=0 node within about the same distance
     * as the true nearest node is returned.
     *
     * If no such node is found either, the true nearest node to p is returned.
     *
     * Finally, if a node is not found at all, null is returned.
     *
     * @param p the screen point
     * @param predicate this parameter imposes a condition on the returned object, e.g.
     *        give the nearest node that is tagged.
     * @param useSelected make search depend on selection
     * @param preferredRefs primitives, whose nodes we prefer
     *
     * @return A node within snap-distance to point p, that is chosen by the algorithm described.
     * @since 6065
     */
    public final Node getNearestNode(Point p, Predicate<OsmPrimitive> predicate,
            boolean useSelected, Collection<OsmPrimitive> preferredRefs) {

        Map<Double, List<Node>> nlists = getNearestNodesImpl(p, predicate);
        if (nlists.isEmpty()) return null;

        if (preferredRefs != null && preferredRefs.isEmpty()) preferredRefs = null;
        Node ntsel = null, ntnew = null, ntref = null;
        boolean useNtsel = useSelected;
        double minDistSq = nlists.keySet().iterator().next();

        for (Entry<Double, List<Node>> entry : nlists.entrySet()) {
            Double distSq = entry.getKey();
            for (Node nd : entry.getValue()) {
                // find the nearest selected node
                if (ntsel == null && nd.isSelected()) {
                    ntsel = nd;
                    // if there are multiple nearest nodes, prefer the one
                    // that is selected. This is required in order to drag
                    // the selected node if multiple nodes have the same
                    // coordinates (e.g. after unglue)
                    useNtsel |= Utils.equalsEpsilon(distSq, minDistSq);
                }
                if (ntref == null && preferredRefs != null && Utils.equalsEpsilon(distSq, minDistSq)) {
                    List<OsmPrimitive> ndRefs = nd.getReferrers();
                    if (preferredRefs.stream().anyMatch(ndRefs::contains)) {
                        ntref = nd;
                    }
                }
                // find the nearest newest node that is within about the same
                // distance as the true nearest node
                if (ntnew == null && nd.isNew() && (distSq-minDistSq < 1)) {
                    ntnew = nd;
                }
            }
        }

        // take nearest selected, nearest new or true nearest node to p, in that order
        if (ntsel != null && useNtsel)
            return ntsel;
        if (ntref != null)
            return ntref;
        if (ntnew != null)
            return ntnew;
        return nlists.values().iterator().next().get(0);
    }

    /**
     * Convenience method to {@link #getNearestNode(Point, Predicate, boolean)}.
     * @param p the screen point
     * @param predicate this parameter imposes a condition on the returned object, e.g.
     *        give the nearest node that is tagged.
     *
     * @return The nearest node to point p.
     */
    public final Node getNearestNode(Point p, Predicate<OsmPrimitive> predicate) {
        return getNearestNode(p, predicate, true);
    }

    /**
     * The *result* does not depend on the current map selection state, neither does the result *order*.
     * It solely depends on the distance to point p.
     * @param p the screen point
     * @param predicate this parameter imposes a condition on the returned object, e.g.
     *        give the nearest node that is tagged.
     *
     * @return a sorted map with the keys representing the perpendicular
     *      distance of their associated way segments to point p.
     */
    private Map<Double, List<WaySegment>> getNearestWaySegmentsImpl(Point p, Predicate<OsmPrimitive> predicate) {
        Map<Double, List<WaySegment>> nearestMap = new TreeMap<>();
        DataSet ds = MainApplication.getLayerManager().getActiveDataSet();

        if (ds != null) {
            double snapDistanceSq = Config.getPref().getInt("mappaint.segment.snap-distance", 10);
            snapDistanceSq *= snapDistanceSq;

            for (Way w : ds.searchWays(getBBox(p, Config.getPref().getInt("mappaint.segment.snap-distance", 10)))) {
                if (!predicate.test(w)) {
                    continue;
                }
                Node lastN = null;
                int i = -2;
                for (Node n : w.getNodes()) {
                    i++;
                    if (n.isDeleted() || n.isIncomplete()) { //FIXME: This shouldn't happen, raise exception?
                        continue;
                    }
                    if (lastN == null) {
                        lastN = n;
                        continue;
                    }

                    Point2D pA = getPoint2D(lastN);
                    Point2D pB = getPoint2D(n);
                    double c = pA.distanceSq(pB);
                    double a = p.distanceSq(pB);
                    double b = p.distanceSq(pA);

                    /* perpendicular distance squared
                     * loose some precision to account for possible deviations in the calculation above
                     * e.g. if identical (A and B) come about reversed in another way, values may differ
                     * -- zero out least significant 32 dual digits of mantissa..
                     */
                    double perDistSq = Double.longBitsToDouble(
                            Double.doubleToLongBits(a - (a - b + c) * (a - b + c) / 4 / c)
                            >> 32 << 32); // resolution in numbers with large exponent not needed here..

                    if (perDistSq < snapDistanceSq && a < c + snapDistanceSq && b < c + snapDistanceSq) {
                        nearestMap.computeIfAbsent(perDistSq, k -> new LinkedList<>()).add(new WaySegment(w, i));
                    }

                    lastN = n;
                }
            }
        }

        return nearestMap;
    }

    /**
     * The result *order* depends on the current map selection state.
     * Segments within 10px of p are searched and sorted by their distance to {@code p},
     * then, within groups of equally distant segments, prefer those that are selected.
     *
     * @param p the point for which to search the nearest segments.
     * @param ignore a collection of segments which are not to be returned.
     * @param predicate the returned objects have to fulfill certain properties.
     *
     * @return all segments within 10px of p that are not in ignore,
     *          sorted by their perpendicular distance.
     */
    public final List<WaySegment> getNearestWaySegments(Point p,
            Collection<WaySegment> ignore, Predicate<OsmPrimitive> predicate) {
        List<WaySegment> nearestList = new ArrayList<>();
        List<WaySegment> unselected = new LinkedList<>();

        for (List<WaySegment> wss : getNearestWaySegmentsImpl(p, predicate).values()) {
            // put selected waysegs within each distance group first
            // makes the order of nearestList dependent on current selection state
            for (WaySegment ws : wss) {
                (ws.getWay().isSelected() ? nearestList : unselected).add(ws);
            }
            nearestList.addAll(unselected);
            unselected.clear();
        }
        if (ignore != null) {
            nearestList.removeAll(ignore);
        }

        return nearestList;
    }

    /**
     * The result *order* depends on the current map selection state.
     *
     * @param p the point for which to search the nearest segments.
     * @param predicate the returned objects have to fulfill certain properties.
     *
     * @return all segments within 10px of p, sorted by their perpendicular distance.
     * @see #getNearestWaySegments(Point, Collection, Predicate)
     */
    public final List<WaySegment> getNearestWaySegments(Point p, Predicate<OsmPrimitive> predicate) {
        return getNearestWaySegments(p, null, predicate);
    }

    /**
     * The *result* depends on the current map selection state IF use_selected is true.
     *
     * @param p the point for which to search the nearest segment.
     * @param predicate the returned object has to fulfill certain properties.
     * @param useSelected whether selected way segments should be preferred.
     *
     * @return The nearest way segment to point p,
     *      and, depending on use_selected, prefers a selected way segment, if found.
     * @see #getNearestWaySegments(Point, Collection, Predicate)
     */
    public final WaySegment getNearestWaySegment(Point p, Predicate<OsmPrimitive> predicate, boolean useSelected) {
        WaySegment wayseg = null;
        WaySegment ntsel = null;

        for (List<WaySegment> wslist : getNearestWaySegmentsImpl(p, predicate).values()) {
            if (wayseg != null && ntsel != null) {
                break;
            }
            for (WaySegment ws : wslist) {
                if (wayseg == null) {
                    wayseg = ws;
                }
                if (ntsel == null && ws.getWay().isSelected()) {
                    ntsel = ws;
                }
            }
        }

        return (ntsel != null && useSelected) ? ntsel : wayseg;
    }

    /**
     * The *result* depends on the current map selection state IF use_selected is true.
     *
     * @param p the point for which to search the nearest segment.
     * @param predicate the returned object has to fulfill certain properties.
     * @param useSelected whether selected way segments should be preferred.
     * @param preferredRefs - prefer segments related to these primitives, may be null
     *
     * @return The nearest way segment to point p,
     *      and, depending on use_selected, prefers a selected way segment, if found.
     * Also prefers segments of ways that are related to one of preferredRefs primitives
     *
     * @see #getNearestWaySegments(Point, Collection, Predicate)
     * @since 6065
     */
    public final WaySegment getNearestWaySegment(Point p, Predicate<OsmPrimitive> predicate,
            boolean useSelected, Collection<OsmPrimitive> preferredRefs) {
        WaySegment wayseg = null;
        if (preferredRefs != null && preferredRefs.isEmpty())
            preferredRefs = null;

        for (List<WaySegment> wslist : getNearestWaySegmentsImpl(p, predicate).values()) {
            for (WaySegment ws : wslist) {
                if (wayseg == null) {
                    wayseg = ws;
                }
                if (useSelected && ws.getWay().isSelected()) {
                    return ws;
                }
                if (!Utils.isEmpty(preferredRefs)) {
                    // prefer ways containing given nodes
                    if (preferredRefs.contains(ws.getFirstNode()) || preferredRefs.contains(ws.getSecondNode())) {
                        return ws;
                    }
                    Collection<OsmPrimitive> wayRefs = ws.getWay().getReferrers();
                    // prefer member of the given relations
                    for (OsmPrimitive ref: preferredRefs) {
                        if (ref instanceof Relation && wayRefs.contains(ref)) {
                            return ws;
                        }
                    }
                }
            }
        }
        return wayseg;
    }

    /**
     * Convenience method to {@link #getNearestWaySegment(Point, Predicate, boolean)}.
     * @param p the point for which to search the nearest segment.
     * @param predicate the returned object has to fulfill certain properties.
     *
     * @return The nearest way segment to point p.
     */
    public final WaySegment getNearestWaySegment(Point p, Predicate<OsmPrimitive> predicate) {
        return getNearestWaySegment(p, predicate, true);
    }

    /**
     * The *result* does not depend on the current map selection state,
     * neither does the result *order*.
     * It solely depends on the perpendicular distance to point p.
     *
     * @param p the point for which to search the nearest ways.
     * @param ignore a collection of ways which are not to be returned.
     * @param predicate the returned object has to fulfill certain properties.
     *
     * @return all nearest ways to the screen point given that are not in ignore.
     * @see #getNearestWaySegments(Point, Collection, Predicate)
     */
    public final List<Way> getNearestWays(Point p,
            Collection<Way> ignore, Predicate<OsmPrimitive> predicate) {
        Set<Way> wset = new HashSet<>();

        List<Way> nearestList = getNearestWaySegmentsImpl(p, predicate).values().stream()
                .flatMap(Collection::stream)
                .filter(ws -> wset.add(ws.getWay()))
                .map(ws -> ws.getWay())
                .collect(Collectors.toList());
        if (ignore != null) {
            nearestList.removeAll(ignore);
        }

        return nearestList;
    }

    /**
     * The *result* does not depend on the current map selection state,
     * neither does the result *order*.
     * It solely depends on the perpendicular distance to point p.
     *
     * @param p the point for which to search the nearest ways.
     * @param predicate the returned object has to fulfill certain properties.
     *
     * @return all nearest ways to the screen point given.
     * @see #getNearestWays(Point, Collection, Predicate)
     */
    public final List<Way> getNearestWays(Point p, Predicate<OsmPrimitive> predicate) {
        return getNearestWays(p, null, predicate);
    }

    /**
     * The *result* depends on the current map selection state.
     *
     * @param p the point for which to search the nearest segment.
     * @param predicate the returned object has to fulfill certain properties.
     *
     * @return The nearest way to point p, prefer a selected way if there are multiple nearest.
     * @see #getNearestWaySegment(Point, Predicate)
     */
    public final Way getNearestWay(Point p, Predicate<OsmPrimitive> predicate) {
        WaySegment nearestWaySeg = getNearestWaySegment(p, predicate);
        return (nearestWaySeg == null) ? null : nearestWaySeg.getWay();
    }

    /**
     * The *result* does not depend on the current map selection state,
     * neither does the result *order*.
     * It solely depends on the distance to point p.
     *
     * First, nodes will be searched. If there are nodes within BBox found,
     * return a collection of those nodes only.
     *
     * If no nodes are found, search for nearest ways. If there are ways
     * within BBox found, return a collection of those ways only.
     *
     * If nothing is found, return an empty collection.
     *
     * @param p The point on screen.
     * @param ignore a collection of ways which are not to be returned.
     * @param predicate the returned object has to fulfill certain properties.
     *
     * @return Primitives nearest to the given screen point that are not in ignore.
     * @see #getNearestNodes(Point, Collection, Predicate)
     * @see #getNearestWays(Point, Collection, Predicate)
     */
    public final List<OsmPrimitive> getNearestNodesOrWays(Point p,
            Collection<OsmPrimitive> ignore, Predicate<OsmPrimitive> predicate) {
        List<OsmPrimitive> nearestList = Collections.emptyList();
        OsmPrimitive osm = getNearestNodeOrWay(p, predicate, false);

        if (osm != null) {
            if (osm instanceof Node) {
                nearestList = new ArrayList<>(getNearestNodes(p, predicate));
            } else if (osm instanceof Way) {
                nearestList = new ArrayList<>(getNearestWays(p, predicate));
            }
            if (ignore != null) {
                nearestList.removeAll(ignore);
            }
        }

        return nearestList;
    }

    /**
     * The *result* does not depend on the current map selection state,
     * neither does the result *order*.
     * It solely depends on the distance to point p.
     *
     * @param p The point on screen.
     * @param predicate the returned object has to fulfill certain properties.
     * @return Primitives nearest to the given screen point.
     * @see #getNearestNodesOrWays(Point, Collection, Predicate)
     */
    public final List<OsmPrimitive> getNearestNodesOrWays(Point p, Predicate<OsmPrimitive> predicate) {
        return getNearestNodesOrWays(p, null, predicate);
    }

    /**
     * This is used as a helper routine to {@link #getNearestNodeOrWay(Point, Predicate, boolean)}
     * It decides, whether to yield the node to be tested or look for further (way) candidates.
     *
     * @param osm node to check
     * @param p point clicked
     * @param useSelected whether to prefer selected nodes
     * @return true, if the node fulfills the properties of the function body
     */
    private boolean isPrecedenceNode(Node osm, Point p, boolean useSelected) {
        if (osm != null) {
            if (p.distanceSq(getPoint2D(osm)) <= (4*4)) return true;
            if (osm.isTagged()) return true;
            if (useSelected && osm.isSelected()) return true;
        }
        return false;
    }

    /**
     * The *result* depends on the current map selection state IF use_selected is true.
     *
     * IF use_selected is true, use {@link #getNearestNode(Point, Predicate)} to find
     * the nearest, selected node.  If not found, try {@link #getNearestWaySegment(Point, Predicate)}
     * to find the nearest selected way.
     *
     * IF use_selected is false, or if no selected primitive was found, do the following.
     *
     * If the nearest node found is within 4px of p, simply take it.
     * Else, find the nearest way segment. Then, if p is closer to its
     * middle than to the node, take the way segment, else take the node.
     *
     * Finally, if no nearest primitive is found at all, return null.
     *
     * @param p The point on screen.
     * @param predicate the returned object has to fulfill certain properties.
     * @param useSelected whether to prefer primitives that are currently selected or referred by selected primitives
     *
     * @return A primitive within snap-distance to point p,
     *      that is chosen by the algorithm described.
     * @see #getNearestNode(Point, Predicate)
     * @see #getNearestWay(Point, Predicate)
     */
    public final OsmPrimitive getNearestNodeOrWay(Point p, Predicate<OsmPrimitive> predicate, boolean useSelected) {
        Collection<OsmPrimitive> sel;
        DataSet ds = MainApplication.getLayerManager().getActiveDataSet();
        if (useSelected && ds != null) {
            sel = ds.getSelected();
        } else {
            sel = null;
        }
        OsmPrimitive osm = getNearestNode(p, predicate, useSelected, sel);

        if (isPrecedenceNode((Node) osm, p, useSelected)) return osm;
        WaySegment ws;
        if (useSelected) {
            ws = getNearestWaySegment(p, predicate, useSelected, sel);
        } else {
            ws = getNearestWaySegment(p, predicate, useSelected);
        }
        if (ws == null) return osm;

        if ((ws.getWay().isSelected() && useSelected) || osm == null) {
            // either (no _selected_ nearest node found, if desired) or no nearest node was found
            osm = ws.getWay();
        } else {
            int maxWaySegLenSq = 3*PROP_SNAP_DISTANCE.get();
            maxWaySegLenSq *= maxWaySegLenSq;

            Point2D wp1 = getPoint2D(ws.getFirstNode());
            Point2D wp2 = getPoint2D(ws.getSecondNode());

            // is wayseg shorter than maxWaySegLenSq and
            // is p closer to the middle of wayseg  than  to the nearest node?
            if (wp1.distanceSq(wp2) < maxWaySegLenSq &&
                    p.distanceSq(project(0.5, wp1, wp2)) < p.distanceSq(getPoint2D((Node) osm))) {
                osm = ws.getWay();
            }
        }
        return osm;
    }

    /**
     * if r = 0 returns a, if r=1 returns b,
     * if r = 0.5 returns center between a and b, etc..
     *
     * @param r scale value
     * @param a root of vector
     * @param b vector
     * @return new point at a + r*(ab)
     */
    public static Point2D project(double r, Point2D a, Point2D b) {
        Point2D ret = null;

        if (a != null && b != null) {
            ret = new Point2D.Double(a.getX() + r*(b.getX()-a.getX()),
                    a.getY() + r*(b.getY()-a.getY()));
        }
        return ret;
    }

    /**
     * The *result* does not depend on the current map selection state, neither does the result *order*.
     * It solely depends on the distance to point p.
     *
     * @param p The point on screen.
     * @param ignore a collection of ways which are not to be returned.
     * @param predicate the returned object has to fulfill certain properties.
     *
     * @return a list of all objects that are nearest to point p and
     *          not in ignore or an empty list if nothing was found.
     */
    public final List<OsmPrimitive> getAllNearest(Point p,
            Collection<OsmPrimitive> ignore, Predicate<OsmPrimitive> predicate) {
        Set<Way> wset = new HashSet<>();

        // add nearby ways
        List<OsmPrimitive> nearestList = getNearestWaySegmentsImpl(p, predicate).values().stream()
                .flatMap(Collection::stream)
                .filter(ws -> wset.add(ws.getWay()))
                .map(ws -> ws.getWay())
                .collect(Collectors.toList());

        // add nearby nodes
        getNearestNodesImpl(p, predicate).values()
                .forEach(nearestList::addAll);

        // add parent relations of nearby nodes and ways
        Set<OsmPrimitive> parentRelations = nearestList.stream()
                .flatMap(o -> o.referrers(Relation.class))
                .filter(predicate)
                .collect(Collectors.toSet());
        nearestList.addAll(parentRelations);

        if (ignore != null) {
            nearestList.removeAll(ignore);
        }

        return nearestList;
    }

    /**
     * The *result* does not depend on the current map selection state, neither does the result *order*.
     * It solely depends on the distance to point p.
     *
     * @param p The point on screen.
     * @param predicate the returned object has to fulfill certain properties.
     *
     * @return a list of all objects that are nearest to point p
     *          or an empty list if nothing was found.
     * @see #getAllNearest(Point, Collection, Predicate)
     */
    public final List<OsmPrimitive> getAllNearest(Point p, Predicate<OsmPrimitive> predicate) {
        return getAllNearest(p, null, predicate);
    }

    /**
     * Returns the projection to be used in calculating stuff.
     * @return The projection to be used in calculating stuff.
     */
    public Projection getProjection() {
        return state.getProjection();
    }

    @Override
    public String helpTopic() {
        String n = getClass().getName();
        return n.substring(n.lastIndexOf('.')+1);
    }

    /**
     * Return a ID which is unique as long as viewport dimensions are the same
     * @return A unique ID, as long as viewport dimensions are the same
     */
    public int getViewID() {
        EastNorth center = getCenter();
        String x = new StringBuilder().append(center.east())
                          .append('_').append(center.north())
                          .append('_').append(getScale())
                          .append('_').append(getWidth())
                          .append('_').append(getHeight())
                          .append('_').append(getProjection()).toString();
        CRC32 id = new CRC32();
        id.update(x.getBytes(StandardCharsets.UTF_8));
        return (int) id.getValue();
    }

    /**
     * Set new cursor.
     * @param cursor The new cursor to use.
     * @param reference A reference object that can be passed to the next set/reset calls to identify the caller.
     */
    public void setNewCursor(Cursor cursor, Object reference) {
        cursorManager.setNewCursor(cursor, reference);
    }

    /**
     * Set new cursor.
     * @param cursor the type of predefined cursor
     * @param reference A reference object that can be passed to the next set/reset calls to identify the caller.
     */
    public void setNewCursor(int cursor, Object reference) {
        setNewCursor(Cursor.getPredefinedCursor(cursor), reference);
    }

    /**
     * Remove the new cursor and reset to previous
     * @param reference Cursor reference
     */
    public void resetCursor(Object reference) {
        cursorManager.resetCursor(reference);
    }

    /**
     * Gets the cursor manager that is used for this NavigatableComponent.
     * @return The cursor manager.
     */
    public CursorManager getCursorManager() {
        return cursorManager;
    }

    /**
     * Get a max scale for projection that describes world in 1/512 of the projection unit
     * @return max scale
     */
    public double getMaxScale() {
        ProjectionBounds world = getMaxProjectionBounds();
        return Math.max(
            world.maxNorth-world.minNorth,
            world.maxEast-world.minEast
        )/512;
    }

    /**
     * Listener for mouse movement events. Used to detect when primitives are being hovered over with the mouse pointer
     * so that registered {@link PrimitiveHoverListener}s can be notified.
     */
    private class PrimitiveHoverMouseListener extends MouseAdapter {
        @Override
        public void mouseMoved(MouseEvent e) {
            OsmPrimitive hovered = getNearestNodeOrWay(e.getPoint(), isSelectablePredicate, true);
            updateHoveredPrimitive(hovered, e);
        }

        @Override
        public void mouseExited(MouseEvent e) {
            updateHoveredPrimitive(null, e);
        }
    }
}
