package org.example;

import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public class SynchronizedDeadlock {
	private final Object lock1 = new Object();
	private final Semaphore planner = new Semaphore(0, true);
	private final Object lock2 = new Object();

	public void thread1() {
		synchronized (lock1) {
			try {
				System.out.println("thread 1 a4 lock1");
				planner.release(1);
				planner.acquire(1);
				System.out.println("thread 1 b4 lock2");
				synchronized (lock2) {
					System.out.println("thread 1 a4 lock2");
				}
			} catch (InterruptedException ignored) {}
		}
		System.out.println("thread 1 exit");
	}

	public void thread2() {
		try {
			planner.acquire(1);
		} catch (InterruptedException ignored) {
			System.err.println("thread 2 wasn't let to go");
			return;
		}
		System.out.println("thread 2 b4 lock2");
		synchronized (lock2) {
			System.out.println("thread 2 a4 lock2");
			planner.release(1);
			System.out.println("thread 2 b4 lock1");
			synchronized (lock1) {
				System.out.println("thread 2 a4 lock1");
			}
		}
		System.out.println("thread 2 exit");
	}

	public void launch() {
		try (var exec = Executors.newThreadPerTaskExecutor(
			Thread.ofVirtual().name("Locker-", 1).factory()
		)) {
			//"Reverse" order so that thread2 can be the first to acquire the permit.
			exec.execute(this::thread2);
			exec.execute(this::thread1);
		}
	}

	// Watch https://bugs.openjdk.org/browse/JDK-8356870
	public static void main(String[] args) {
		new SynchronizedDeadlock().launch();
	}
}
