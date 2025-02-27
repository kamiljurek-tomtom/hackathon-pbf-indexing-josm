// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.gui.layer.markerlayer;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Stroke;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.swing.ImageIcon;

import org.openstreetmap.josm.data.Preferences;
import org.openstreetmap.josm.data.coor.CachedLatLon;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.ILatLon;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.gpx.GpxConstants;
import org.openstreetmap.josm.data.gpx.WayPoint;
import org.openstreetmap.josm.data.osm.search.SearchCompiler.Match;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.GpxLayer;
import org.openstreetmap.josm.gui.preferences.display.GPXSettingsPanel;
import org.openstreetmap.josm.spi.preferences.PreferenceChangedListener;
import org.openstreetmap.josm.tools.Destroyable;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.template_engine.ParseError;
import org.openstreetmap.josm.tools.template_engine.TemplateEngineDataProvider;
import org.openstreetmap.josm.tools.template_engine.TemplateEntry;
import org.openstreetmap.josm.tools.template_engine.TemplateParser;

/**
 * Basic marker class. Requires a position, and supports
 * a custom icon and a name.
 *
 * This class is also used to create appropriate Marker-type objects
 * when waypoints are imported.
 *
 * It hosts a public list object, named makers, containing implementations of
 * the MarkerMaker interface. Whenever a Marker needs to be created, each
 * object in makers is called with the waypoint parameters (Lat/Lon and tag
 * data), and the first one to return a Marker object wins.
 *
 * By default, one the list contains one default "Maker" implementation that
 * will create AudioMarkers for supported audio files, ImageMarkers for supported image
 * files, and WebMarkers for everything else. (The creation of a WebMarker will
 * fail if there's no valid URL in the &lt;link&gt; tag, so it might still make sense
 * to add Makers for such waypoints at the end of the list.)
 *
 * The default implementation only looks at the value of the &lt;link&gt; tag inside
 * the &lt;wpt&gt; tag of the GPX file.
 *
 * <h2>HowTo implement a new Marker</h2>
 * <ul>
 * <li> Subclass Marker or ButtonMarker and override <code>containsPoint</code>
 *      if you like to respond to user clicks</li>
 * <li> Override paint, if you want a custom marker look (not "a label and a symbol")</li>
 * <li> Implement MarkerCreator to return a new instance of your marker class</li>
 * <li> In you plugin constructor, add an instance of your MarkerCreator
 *      implementation either on top or bottom of Marker.markerProducers.
 *      Add at top, if your marker should overwrite an current marker or at bottom
 *      if you only add a new marker style.</li>
 * </ul>
 *
 * @author Frederik Ramm
 */
public class Marker implements TemplateEngineDataProvider, ILatLon, Destroyable {

    /**
     * Plugins can add their Marker creation stuff at the bottom or top of this list
     * (depending on whether they want to override default behaviour or just add new stuff).
     */
    private static final List<MarkerProducers> markerProducers = new LinkedList<>();

    // Add one Marker specifying the default behaviour.
    static {
        Marker.markerProducers.add(new DefaultMarkerProducers());
    }

    /**
     * Add a new marker producers at the end of the JOSM list.
     * @param mp a new marker producers
     * @since 11850
     */
    public static void appendMarkerProducer(MarkerProducers mp) {
        markerProducers.add(mp);
    }

    /**
     * Add a new marker producers at the beginning of the JOSM list.
     * @param mp a new marker producers
     * @since 11850
     */
    public static void prependMarkerProducer(MarkerProducers mp) {
        markerProducers.add(0, mp);
    }

    /**
     * Returns an object of class Marker or one of its subclasses
     * created from the parameters given.
     *
     * @param wpt waypoint data for marker
     * @param relativePath An path to use for constructing relative URLs or
     *        <code>null</code> for no relative URLs
     * @param parentLayer the <code>MarkerLayer</code> that will contain the created <code>Marker</code>
     * @param time time of the marker in seconds since epoch
     * @param offset double in seconds as the time offset of this marker from
     *        the GPX file from which it was derived (if any).
     * @return a new Marker object
     */
    public static Collection<Marker> createMarkers(WayPoint wpt, File relativePath, MarkerLayer parentLayer, double time, double offset) {
        return Marker.markerProducers.stream()
                .map(maker -> maker.createMarkers(wpt, relativePath, parentLayer, time, offset))
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

    public static final String MARKER_OFFSET = "waypointOffset";
    public static final String MARKER_FORMATTED_OFFSET = "formattedWaypointOffset";

    public static final String LABEL_PATTERN_AUTO = "?{ '{name} ({desc})' | '{name} ({cmt})' | '{name}' | '{desc}' | '{cmt}' }";
    public static final String LABEL_PATTERN_NAME = "{name}";
    public static final String LABEL_PATTERN_DESC = "{desc}";

    private final TemplateEngineDataProvider dataProvider;
    private final String text;

    protected final ImageIcon symbol;
    private BufferedImage redSymbol;
    public final MarkerLayer parentLayer;
    /** Absolute time of marker in seconds since epoch */
    public double time;
    /** Time offset in seconds from the gpx point from which it was derived, may be adjusted later to sync with other data, so not final */
    public double offset;

    private String cachedText;
    private static Map<GpxLayer, String> cachedTemplates = new HashMap<>();
    private String cachedDefaultTemplate;

    private CachedLatLon coor;
    private PreferenceChangedListener listener = l -> updateText();

    private boolean erroneous;

    public Marker(LatLon ll, TemplateEngineDataProvider dataProvider, String iconName, MarkerLayer parentLayer,
            double time, double offset) {
        this(ll, dataProvider, null, iconName, parentLayer, time, offset);
    }

    public Marker(LatLon ll, String text, String iconName, MarkerLayer parentLayer, double time, double offset) {
        this(ll, null, text, iconName, parentLayer, time, offset);
    }

    private Marker(LatLon ll, TemplateEngineDataProvider dataProvider, String text, String iconName, MarkerLayer parentLayer,
            double time, double offset) {
        setCoor(ll);

        this.offset = offset;
        this.time = time;
        /* tell icon checking that we expect these names to exist */
        // /* ICON(markers/) */"Bridge"
        // /* ICON(markers/) */"Crossing"
        this.symbol = iconName != null ? ImageProvider.getIfAvailable("markers", iconName) : null;
        this.parentLayer = parentLayer;

        this.dataProvider = dataProvider;
        this.text = text;

        Preferences.main().addKeyPreferenceChangeListener(getPreferenceKey(), listener);
    }

    /**
     * Convert Marker to WayPoint so it can be exported to a GPX file.
     *
     * Override in subclasses to add all necessary attributes.
     *
     * @return the corresponding WayPoint with all relevant attributes
     */
    public WayPoint convertToWayPoint() {
        WayPoint wpt = new WayPoint(getCoor());
        if (time > 0d) {
            wpt.setTimeInMillis((long) (time * 1000));
        }
        if (text != null) {
            wpt.getExtensions().add("josm", "text", text);
        } else if (dataProvider != null) {
            for (String key : dataProvider.getTemplateKeys()) {
                Object value = dataProvider.getTemplateValue(key, false);
                if (value != null && GpxConstants.WPT_KEYS.contains(key)) {
                    wpt.put(key, value);
                }
            }
        }
        return wpt;
    }

    /**
     * Sets the marker's coordinates.
     * @param coor The marker's coordinates (lat/lon)
     */
    public final void setCoor(LatLon coor) {
        this.coor = new CachedLatLon(coor);
    }

    /**
     * Returns the marker's coordinates.
     * @return The marker's coordinates (lat/lon)
     */
    public final LatLon getCoor() {
        return coor;
    }

    /**
     * Sets the marker's projected coordinates.
     * @param eastNorth The marker's projected coordinates (easting/northing)
     */
    public final void setEastNorth(EastNorth eastNorth) {
        this.coor = new CachedLatLon(eastNorth);
    }

    /**
     * @since 12725
     */
    @Override
    public double lon() {
        return coor == null ? Double.NaN : coor.lon();
    }

    /**
     * @since 12725
     */
    @Override
    public double lat() {
        return coor == null ? Double.NaN : coor.lat();
    }

    /**
     * Checks whether the marker display area contains the given point.
     * Markers not interested in mouse clicks may always return false.
     *
     * @param p The point to check
     * @return <code>true</code> if the marker "hotspot" contains the point.
     */
    public boolean containsPoint(Point p) {
        return false;
    }

    /**
     * Called when the mouse is clicked in the marker's hotspot. Never
     * called for markers which always return false from containsPoint.
     *
     * @param ev A dummy ActionEvent
     */
    public void actionPerformed(ActionEvent ev) {
        // Do nothing
    }

    /**
     * Paints the marker.
     * @param g graphics context
     * @param mv map view
     * @param mousePressed true if the left mouse button is pressed
     * @param showTextOrIcon true if text and icon shall be drawn
     */
    public void paint(Graphics2D g, MapView mv, boolean mousePressed, boolean showTextOrIcon) {
        Point screen = mv.getPoint(this);
        int size2 = parentLayer.markerSize / 2;

        if (symbol != null && showTextOrIcon) {
            paintIcon(mv, g, screen.x-symbol.getIconWidth()/2, screen.y-symbol.getIconHeight()/2);
        } else {
            Stroke stroke = g.getStroke();
            g.setStroke(parentLayer.markerStroke);
            g.drawLine(screen.x - size2, screen.y - size2, screen.x + size2, screen.y + size2);
            g.drawLine(screen.x + size2, screen.y - size2, screen.x - size2, screen.y + size2);
            g.setStroke(stroke);
        }

        String labelText = getText();
        if (!labelText.isEmpty() && showTextOrIcon) {
            g.drawString(labelText, screen.x + size2 + 2, screen.y + size2);
        }
    }

    protected void paintIcon(MapView mv, Graphics g, int x, int y) {
        if (!erroneous) {
            symbol.paintIcon(mv, g, x, y);
        } else {
            if (redSymbol == null) {
                int width = symbol.getIconWidth();
                int height = symbol.getIconHeight();

                redSymbol = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D gbi = redSymbol.createGraphics();
                gbi.drawImage(symbol.getImage(), 0, 0, null);
                gbi.setColor(Color.RED);
                gbi.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, 0.666f));
                gbi.fillRect(0, 0, width, height);
                gbi.dispose();
            }
            g.drawImage(redSymbol, x, y, mv);
        }
    }

    protected String getTextTemplateKey() {
        return "markers.pattern";
    }

    private String getTextTemplate() {
        String tmpl;
        if (cachedTemplates.containsKey(parentLayer.fromLayer)) {
            tmpl = cachedTemplates.get(parentLayer.fromLayer);
        } else {
            tmpl = GPXSettingsPanel.getLayerPref(parentLayer.fromLayer, getTextTemplateKey());
            cachedTemplates.put(parentLayer.fromLayer, tmpl);
        }
        return tmpl;
    }

    private String getDefaultTextTemplate() {
        if (cachedDefaultTemplate == null) {
            cachedDefaultTemplate = GPXSettingsPanel.getLayerPref(null, getTextTemplateKey());
        }
        return cachedDefaultTemplate;
    }

    /**
     * Returns the Text which should be displayed, depending on chosen preference
     * @return Text of the label
     */
    public String getText() {
        if (text != null) {
            return text;
        } else if (cachedText == null) {
            TemplateEntry template;
            String templateString = getTextTemplate();
            try {
                template = new TemplateParser(templateString).parse();
            } catch (ParseError e) {
                Logging.debug(e);
                String def = getDefaultTextTemplate();
                Logging.warn("Unable to parse template engine pattern ''{0}'' for property {1}. Using default (''{2}'') instead",
                        templateString, getTextTemplateKey(), def);
                try {
                    template = new TemplateParser(def).parse();
                } catch (ParseError e1) {
                    Logging.error(e1);
                    cachedText = "";
                    return "";
                }
            }
            StringBuilder sb = new StringBuilder();
            template.appendText(sb, this);
            cachedText = sb.toString();

        }
        return cachedText;
    }

    /**
     * Called when the template changes
     */
    public void updateText() {
        cachedText = null;
        cachedDefaultTemplate = null;
        cachedTemplates.clear();
    }

    @Override
    public Collection<String> getTemplateKeys() {
        Collection<String> result;
        if (dataProvider != null) {
            result = dataProvider.getTemplateKeys();
        } else {
            result = new ArrayList<>();
        }
        result.add(MARKER_FORMATTED_OFFSET);
        result.add(MARKER_OFFSET);
        return result;
    }

    private String formatOffset() {
        int wholeSeconds = (int) (offset + 0.5);
        if (wholeSeconds < 60)
            return Integer.toString(wholeSeconds);
        else if (wholeSeconds < 3600)
            return String.format("%d:%02d", wholeSeconds / 60, wholeSeconds % 60);
        else
            return String.format("%d:%02d:%02d", wholeSeconds / 3600, (wholeSeconds % 3600)/60, wholeSeconds % 60);
    }

    @Override
    public Object getTemplateValue(String name, boolean special) {
        if (MARKER_FORMATTED_OFFSET.equals(name))
            return formatOffset();
        else if (MARKER_OFFSET.equals(name))
            return offset;
        else if (dataProvider != null)
            return dataProvider.getTemplateValue(name, special);
        else
            return null;
    }

    @Override
    public boolean evaluateCondition(Match condition) {
        throw new UnsupportedOperationException();
    }

    /**
     * Determines if this marker is erroneous.
     * @return {@code true} if this markers has any kind of error, {@code false} otherwise
     * @since 6299
     */
    public final boolean isErroneous() {
        return erroneous;
    }

    /**
     * Sets this marker erroneous or not.
     * @param erroneous {@code true} if this markers has any kind of error, {@code false} otherwise
     * @since 6299
     */
    public final void setErroneous(boolean erroneous) {
        this.erroneous = erroneous;
        if (!erroneous) {
            redSymbol = null;
        }
    }

    @Override
    public void destroy() {
        cachedTemplates.clear();
        Preferences.main().removeKeyPreferenceChangeListener(getPreferenceKey(), listener);
    }

    private String getPreferenceKey() {
        return "draw.rawgps." + getTextTemplateKey();
    }
}
