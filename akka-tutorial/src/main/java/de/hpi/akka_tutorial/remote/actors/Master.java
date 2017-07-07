package de.hpi.akka_tutorial.remote.actors;

import akka.actor.*;
import akka.japi.pf.DeciderBuilder;
import akka.remote.RemoteScope;
import akka.routing.ActorRefRoutee;
import akka.routing.RoundRobinRoutingLogic;
import akka.routing.Routee;
import akka.routing.Router;
import scala.concurrent.duration.Duration;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static akka.actor.SupervisorStrategy.escalate;
import static akka.actor.SupervisorStrategy.stop;

/**
 * The master receives number of ranges that it should find all primes in. This is done by delegation to slaves.
 */
public class Master extends AbstractLoggingActor {

	public static Props props(final ActorRef listener) {
		return Props.create(Master.class, () -> new Master(listener));
	}

	private static SupervisorStrategy strategy =
			new OneForOneStrategy(0, Duration.create(1, TimeUnit.SECONDS), DeciderBuilder
					.match(Exception.class, e -> stop())
					.matchAny(o -> escalate())
					.build());

	public static class RangeMessage implements Serializable {

		private static final long serialVersionUID = 1538940836039448197L;

		private final long startNumber;
		private final long endNumber;

		public long getStartNumber() {
			return this.startNumber;
		}

		public long getEndNumber() {
			return this.endNumber;
		}

		public RangeMessage(final long startNumber, final long endNumber) {
			this.startNumber = startNumber;
			this.endNumber = endNumber;
		}
	}

	/**
	 * This message tells that no further {@link RangeMessage}s will be sent. The master should terminate then as soon
	 * as all pending work is done.
	 */
	public static class NoMoreRangesMessage implements Serializable {
	}

	public static class ObjectMessage implements Serializable {

		private static final long serialVersionUID = 4862570515887001983L;

		private final Integer id;
		private final List<Object> objects;

		public Integer getId() {
			return this.id;
		}

		public List<Object> getObjects() {
			return this.objects;
		}

		public ObjectMessage(final Integer id, final List<Object> objects) {
			this.id = id;
			this.objects = objects;
		}
	}

	public static class URIMessage implements Serializable {

		private static final long serialVersionUID = 2786272840353304769L;

		private final String uri;

		public String getURI() {
			return this.uri;
		}

		public URIMessage(String uri) {
			this.uri = uri;
		}
	}

	private Router workerRouter;
	private final ActorRef listener;

	private List<Integer> pendingResponses = new ArrayList<>();
	private int numberOfWorkers = 0;
	private final List<String> strings = new ArrayList<String>();
	private boolean isAcceptingRequests = true;

	public Master(final ActorRef listener) {

		// Save the reference to the Listener actor
		this.listener = listener;

		// Create router with only one local routee
		List<Routee> routees = new ArrayList<Routee>();

		ActorRef workerRef = this.getContext().actorOf(Worker.props());
		this.getContext().watch(workerRef);
		routees.add(new ActorRefRoutee(workerRef));
		this.numberOfWorkers++;

		this.workerRouter = new Router(new RoundRobinRoutingLogic(), routees);
	}

	@Override
	public SupervisorStrategy supervisorStrategy() {
		return Master.strategy;
	}

	@Override
	public Receive createReceive() {
		return receiveBuilder()
				.match(URIMessage.class, this::handle)
				.match(RangeMessage.class, this::handle)
				.match(ObjectMessage.class, this::handle)
				.match(NoMoreRangesMessage.class, this::handle)
				.match(Terminated.class, m -> this.numberOfWorkers--)
				.matchAny(object -> this.log().info(this.getClass().getName() + " received unknown message: " + object.toString()))
				.build();
	}

	private void handle(URIMessage message) {

		// Create a new worker with the given URI
		Address address = AddressFromURIString.parse(message.getURI());
		ActorRef worker = this.getContext().actorOf(Worker.props().withDeploy(new Deploy(new RemoteScope(address))));

		// Add the worker to the watch list and our router
		this.getContext().watch(worker);
		this.workerRouter = this.workerRouter.addRoutee(worker);
		this.numberOfWorkers++;

		this.log().info("{}", this.workerRouter.routees());

		this.log().info("New worker: " + message.getURI());
	}

	private void handle(RangeMessage message) {
		if (!this.isAcceptingRequests) {
			log().warning("Discarding request {}.", message);
			return;
		}

		// Find an ID for this message
		final int messageId = this.pendingResponses.size();

		// Set the pending responses for this message
		this.pendingResponses.add(this.numberOfWorkers);

		// Break the work up into numberOfWorkers chunks of numbers
		final long numberOfNumbers = message.getEndNumber() - message.getStartNumber() + 1;
		final long segmentLength = numberOfNumbers / this.numberOfWorkers;

		for (int i = 0; i < this.numberOfWorkers; i++) {
			// Compute the start and end numbers for this worker
			long startNumber = message.getStartNumber() + (i * segmentLength);
			long endNumber = startNumber + segmentLength - 1;

			// Handle any remainder if this is the last worker
			if (i == this.numberOfWorkers - 1)
				endNumber = message.getEndNumber();

			// Calculate the numbers
			List<Long> numbers = new ArrayList<Long>((int) (endNumber - startNumber) + 1);
			for (long number = startNumber; number <= endNumber; number++)
				numbers.add(number);

			// Send a new message to the router for this subset of numbers
			this.workerRouter.route(new Worker.NumbersMessage(messageId, numbers), this.getSelf());
		}
	}

	private void handle(NoMoreRangesMessage message) {
		this.isAcceptingRequests = false;
		if (!this.hasPendingResponses()) {
			this.stopSelfAndListener();
		}
	}

	/**
	 * Tells if there are still any pending responses.
	 *
	 * @return if there are still any pending responses
	 */
	private boolean hasPendingResponses() {
		for (Integer pendingResponse : pendingResponses) {
			if (pendingResponse != 0) return true;
		}
		return false;
	}

	private void handle(ObjectMessage message) {

		// Add the received objects from the worker to the final result
		for (Object object : message.getObjects())
			this.strings.add(object.toString());

		// Decrement the pending responses for this message
		Integer pending = this.pendingResponses.get(message.getId()) - 1;
		this.pendingResponses.set(message.getId(), pending);

		if (pending == 0) {
			// Notify the listener
			this.listener.tell(new Listener.StringsMessage(this.strings), this.getSelf());

			// Check if work is now complete.
			if (!this.isAcceptingRequests && !this.hasPendingResponses()) {
				this.stopSelfAndListener();
			}
		}
	}

	private void stopSelfAndListener() {
		log().info("Stopping...");
		getSelf().tell(PoisonPill.getInstance(), getSelf());
		this.listener.tell(PoisonPill.getInstance(), getSelf());
	}
}