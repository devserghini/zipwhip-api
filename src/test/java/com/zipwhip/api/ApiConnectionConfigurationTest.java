package com.zipwhip.api;

import org.junit.Test;

import static junit.framework.Assert.assertEquals;

/**
 * Created with IntelliJ IDEA.
 * User: jed
 * Date: 8/29/12
 * Time: 11:21 AM
 */
public class ApiConnectionConfigurationTest {

    @Test
    public void testProdHttpsDefault() {
        assertEquals(ApiConnection.DEFAULT_HTTPS_HOST, ApiConnectionConfiguration.API_HOST);
        assertEquals(ApiConnection.DEFAULT_SIGNALS_HOST, ApiConnectionConfiguration.SIGNALS_HOST);
        assertEquals(ApiConnection.DEFAULT_SIGNALS_PORT, ApiConnectionConfiguration.SIGNALS_PORT);
    }

}
