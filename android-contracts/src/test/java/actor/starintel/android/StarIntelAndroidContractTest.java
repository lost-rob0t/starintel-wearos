package actor.starintel.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StarIntelAndroidContractTest {
    @Test
    public void packageIdsStayIndependent() {
        assertNotEquals(StarIntelAndroidContract.PACKAGE_OPERATOR, StarIntelAndroidContract.PACKAGE_COLLECTOR);
        assertNotEquals(StarIntelAndroidContract.PACKAGE_OPERATOR, StarIntelAndroidContract.PACKAGE_MAPS);
        assertNotEquals(StarIntelAndroidContract.PACKAGE_COLLECTOR, StarIntelAndroidContract.PACKAGE_MAPS);
    }

    @Test
    public void actionsRemainNamespaced() {
        assertTrue(StarIntelAndroidContract.ACTION_OPEN_MAP.startsWith("actor.starintel.action."));
        assertTrue(StarIntelAndroidContract.ACTION_COLLECT.startsWith("actor.starintel.action."));
        assertEquals(1, StarIntelAndroidContract.VERSION);
    }
}
