package com.zipwhip.api.dto;

import java.io.Serializable;

/**
 * Created by IntelliJ IDEA.
 * * Date: Jul 17, 2009
 * Time: 8:23:35 PM
 * To change this template use File | Settings | File Templates.
 */
public class MessageToken implements Serializable {

    private static final long serialVersionUID = 5878234954952365L;

    /**
     * This is the message uuid
     */
    String message;
    long deviceId;
    long contactId;
    String fingerprint;
    /**
     * The message ID of the root message associated with this message token.  In situations
     * where the message token is the root message, the rootMessage and message will be the same.
     * In situations where a sendMessage call returns more than one MessageToken, the rootMessage will
     * be the same across all MessageTokens.
     */
    String rootMessage;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(long deviceId) {
        this.deviceId = deviceId;
    }

    public long getContactId() {
        return contactId;
    }

    public void setContactId(long contactId) {
        this.contactId = contactId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getRootMessage() {
        return rootMessage;
    }

    public void setRootMessage(String rootMessage) {
        this.rootMessage = rootMessage;
    }

    @Override
    public String toString() {
        StringBuilder toStringBuilder = new StringBuilder("==> MessageToken details:");
        toStringBuilder.append("\nMessage: ").append(message);
        toStringBuilder.append("\nRoot Message: ").append(rootMessage);
        toStringBuilder.append("\nDeviceId: ").append(deviceId);
        toStringBuilder.append("\nContactId: ").append(contactId);
        toStringBuilder.append("\nFingerprint: ").append(fingerprint);
        return toStringBuilder.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MessageToken)) return false;

        MessageToken that = (MessageToken) o;

        if (contactId != that.contactId) return false;
        if (deviceId != that.deviceId) return false;
        if (fingerprint != null ? !fingerprint.equals(that.fingerprint) : that.fingerprint != null) return false;
        if (message != null ? !message.equals(that.message) : that.message != null) return false;
        if (rootMessage != null ? !rootMessage.equals(that.rootMessage) : that.rootMessage != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = message != null ? message.hashCode() : 0;
        result = 31 * result + (int) (deviceId ^ (deviceId >>> 32));
        result = 31 * result + (int) (contactId ^ (contactId >>> 32));
        result = 31 * result + (fingerprint != null ? fingerprint.hashCode() : 0);
        result = 31 * result + (rootMessage != null ? rootMessage.hashCode() : 0);
        return result;
    }
}
