// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.gui.datatransfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.actions.CopyAction;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.testutils.JOSMTestRules;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Unit tests of {@link OsmTransferHandler} class.
 */
class OsmTransferHandlerTest {
    /**
     * Prefs to use OSM primitives
     */
    @RegisterExtension
    @SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD")
    public JOSMTestRules test = new JOSMTestRules().preferences().projection().main();

    private final OsmTransferHandler transferHandler = new OsmTransferHandler();

    /**
     * Test of {@link OsmTransferHandler#pasteOn} method
     */
    @Test
    void testPasteOn() {
        DataSet ds1 = new DataSet();
        Node n1 = new Node(new LatLon(43, 1));
        ds1.addPrimitive(n1);
        OsmDataLayer source = new OsmDataLayer(ds1, "source", null);

        CopyAction.copy(source, Collections.singleton(n1));

        DataSet ds2 = new DataSet();
        OsmDataLayer target = new OsmDataLayer(ds2, "target", null);

        transferHandler.pasteOn(target, null);
        assertTrue(n1.equalsEpsilon(ds2.getNodes().iterator().next()));

        ds2.clear();
        assertTrue(ds2.getNodes().isEmpty());

        LatLon pos = new LatLon(55, -5);
        transferHandler.pasteOn(target, ProjectionRegistry.getProjection().latlon2eastNorth(pos));
        assertTrue(pos.equalsEpsilon(ds2.getNodes().iterator().next()));
    }

    /**
     * Test of {@link OsmTransferHandler#pasteTags} method
     */
    @Test
    void testPasteTags() {
        Node n = new Node(LatLon.ZERO);
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(n), "testPasteTags", null));

        ClipboardUtils.copyString("test=ok");
        transferHandler.pasteTags(Collections.singleton(n));

        assertEquals("ok", n.get("test"));
    }
}
