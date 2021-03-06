package com.zipwhip.util;

import com.zipwhip.locators.Locator;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by IntelliJ IDEA.
 * User: Michael
 * Date: 7/6/11
 * Time: 3:37 PM
 * <p/>
 * Locate something via in-memory map
 */
public class MemoryLocator<T> implements Locator<T> {

    private Map<String, T> elements = new HashMap<String, T>();

    public MemoryLocator() {

    }

    public MemoryLocator(Map<String, T> elements) {
        this.elements = elements;
    }

    @Override
    public T locate(String key) {
        return elements.get(key);
    }

    public Map<String, T> getElements() {
        return elements;
    }

    public void setElements(Map<String, T> elements) {
        this.elements = elements;
    }

}
