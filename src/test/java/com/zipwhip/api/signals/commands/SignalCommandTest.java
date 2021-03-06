/**
 * 
 */
package com.zipwhip.api.signals.commands;

import com.zipwhip.api.signals.Signal;
import com.zipwhip.signals.message.Action;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * @author jdinsel
 *
 */
public class SignalCommandTest {

	private SignalCommand command;
	
	@Test
	public void test() {
		Signal signal = new Signal();
		command = new SignalCommand(signal);
		
		assertEquals(Action.SIGNAL, command.getAction());
		assertFalse(command.isBackfill());
		assertEquals(Long.valueOf(-1), Long.valueOf(command.getMaxBackfillVersion()));
	}

	@Test
	public void testBackfillSignal() {
		Signal signal = new Signal();
		command = new SignalCommand(signal);
		
		assertFalse(command.isBackfill());
		command.setMaxBackfillVersion(-1l);
		assertFalse(command.isBackfill());
		command.setMaxBackfillVersion(42l);
		assertTrue(command.isBackfill());
		
		command = new SignalCommand(signal);
		command.setBackfill(true);
		assertTrue(command.isBackfill());
	}
}
