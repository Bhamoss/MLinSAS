package mapek;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import deltaiot.client.Effector;
import deltaiot.client.Probe;
import deltaiot.services.LinkSettings;
import deltaiot.services.QoS;
import smc.runmodes.ActivForms;
import smc.runmodes.Comparison;
import smc.runmodes.MLAdjustment;
import smc.runmodes.MachineLearning;
import smc.runmodes.SMCConnector;
import smc.runmodes.SMCConnector.Mode;
import smc.runmodes.SMCConnector.TaskType;
import util.ConfigLoader;


public class FeedbackLoop {

	//The probe and effector of the network being worked on.
	Probe probe;
	Effector effector;

	
	// Knowledge
	public static final int DISTRIBUTION_GAP = ConfigLoader.getInstance().getDistributionGap();
	public static final boolean human = ConfigLoader.getInstance().getHuman();
	
	Configuration currentConfiguration;
	Configuration previousConfiguration;

	// The steps that are filled in by the planner to adjust to the newly chosen best configuration
	List<PlanningStep> steps = new LinkedList<>();
	
	// The equations for interference on the links
	List<SNREquation> snrEquations = new LinkedList<>();

	// The current adaptation options are the options specific to the current cycle
	List<AdaptationOption> currentAdaptationOptions = new LinkedList<>();
	// The overall adaptation options keeps track of all the encountered options so far
	// Set<AdaptationOption> overallAdaptationOptions = new LinkedHashSet<>();

	List<AdaptationOption> verifiedOptions;

	// SMCConnector smcConnector = new SMCConnector();
	SMCConnector smcConnector;


	Goals goals = Goals.getInstance();

	// Thresholds for when you want to adapt/change the network
	static final int SNR_BELOW_THRESHOLD = 0;
	static final int SNR_UPPER_THRESHOLD = 5;
	static final int ENERGY_CONSUMPTION_THRESHOLD = 5;
	static final int PACKET_LOSS_THRESHOLD = 5;

	// Gets triggered when the difference between the last two cycles is greater than this,
	// but also when it is smaller than minus this
	static final int MOTES_TRAFFIC_THRESHOLD = 10;


	public FeedbackLoop() {
		// Dependant on the run mode, instantiate a different connector
		// TODO maybe move this method to the enum itself
		Mode runmode = ConfigLoader.getInstance().getRunMode();
		switch (runmode) {
			case MACHINELEARNING:
				smcConnector = new MachineLearning();
				break;
			case ACTIVFORM:
				smcConnector = new ActivForms();
				break;
			case COMPARISON:
				smcConnector = new Comparison();
				break;
			case MLADJUSTMENT:
				smcConnector = new MLAdjustment();
				break;
			default:
				throw new RuntimeException(String.format("Unsupported run mode: %s", runmode.val));
		}
	}

	public void setProbe(Probe probe) {
		this.probe = probe;
	}

	public void setEffector(Effector effector) {
		this.effector = effector;
	}

	public void setEquations(List<SNREquation> equations) {
		snrEquations = equations;
	}


	public void start() {
		System.out.println("Feedback loop started.");

		LocalDateTime now;

		// Run the mape-k loop and simulator for the specified amount of cycles
		for (int i = 1; i <= ConfigLoader.getInstance().getAmountOfCycles(); i++) {
		
			if(!human)
			{
				System.out.print(i + ";" + System.currentTimeMillis());
			}
			else
			{
				now = LocalDateTime.now();
				System.out.print(i + "; " + String.format("%02d:%02d:%02d", 
					now.getHour(), now.getMinute(), now.getSecond()) + " ");
			}
			
			// Start the monitor part of the mapek loop
			monitor();
		}
	}


	void monitor() {
		// The method "probe.getAllMotes()" also makes sure the simulator is run for a single cycle
		ArrayList<deltaiot.services.Mote> motes = probe.getAllMotes();
		
		List<Mote> newMotes = new LinkedList<>();
		previousConfiguration = currentConfiguration;
		currentConfiguration = new Configuration();

		// Make a copy of the IoT network in its current state
		Mote newMote;
		Link newLink;

		// Iterate through all the motes of the simulator
		for (deltaiot.services.Mote mote : motes) {

			newMote = new Mote();
			newMote.moteId = mote.getMoteid();
			newMote.energyLevel = mote.getBattery();
			newMote.load = mote.getLoad();
			newMote.queueSize = mote.getCurrentQSize();

			// motesLoad holds a list of the probabilities that certain motes generate packets (probability in range [0, 100])
			currentConfiguration.environment.motesLoad
					.add(new TrafficProbability(mote.getMoteid(), mote.getDataProbability()));
			
			
			// Copy the links and their SNR values
			for (deltaiot.services.Link link : mote.getLinks()) {
				newLink = new Link();
				newLink.source = link.getSource();
				newLink.destination = link.getDest();
				newLink.distribution = link.getDistribution();
				newLink.power = link.getPower();
				newMote.links.add(newLink);
				currentConfiguration.environment.linksSNR.add(new SNR(link.getSource(), link.getDest(), link.getSNR()));
			}
			
			// add the mote to the configuration
			newMotes.add(newMote);
		}

		// This saves the architecture of the system to the new configuration by adding the 
		// new motes which contain all the necessary data
		currentConfiguration.system = new ManagedSystem(newMotes);
		
		//getNetworkQoS(n) returns a list of the QoS
		// values of the n previous cycles.
		//This returns the latest QoS and
		// returns the first (and only) element of the list.
		QoS qos = probe.getNetworkQoS(1).get(0);

		// Adds the QoS of the previous configuration to the current configuration,
		// probably to pass on to the learner so he can use this to online learn
		currentConfiguration.qualities.packetLoss = qos.getPacketLoss();
		currentConfiguration.qualities.energyConsumption = qos.getEnergyConsumption();
		currentConfiguration.qualities.latency = qos.getLatency();

		// Call the next step off the mapek
		analysis();
	}



	void analysis() {

		boolean adaptationRequired = analysisRequired();

		if (!adaptationRequired)
			return;

		// creates a new object for the adaption option
		AdaptationOption newPowerSettingsConfig = new AdaptationOption();
		
		// copy the system = architecture of the network = managed system.java
		newPowerSettingsConfig.system = currentConfiguration.system.getCopy();

		// I think this adapts the power until the SNR reaches zero
		analyzePowerSettings(newPowerSettingsConfig);

		// when there are 2 outgoing links and they are both 100, set one to 0
		// Seems weird, why do this. Its dirty programming, but what is the origin of the problem.
		removePacketDuplication(newPowerSettingsConfig);

		// This adds the possible link distributions to the motes who have 2 outgoing links
		composeAdaptationOptions(newPowerSettingsConfig);

		// Pass the adaptionOptions and the environment (noise and load) to the connector
		smcConnector.setAdaptationOptions(currentAdaptationOptions, currentConfiguration.environment);

		// let the model checker and/or machine learner start to predict which adaption options will
		// fullfill the goals definied in the connector
		smcConnector.verify();

		// the connector changed the adaptionOptions of the feedbackloop directly,
		// to the options it thinks will suffiece the goals
		// verifiedOptions is also an argument of the feedbackloop object
		// and should require a setter...
		verifiedOptions = currentAdaptationOptions;

		// Continue to the planning step.
		planning();
	}

	/**
	 * Sets the distributions for the links of motes with 2 parents to 0-100 respectively.
	 */
	void initializeMoteDistributions(AdaptationOption newConfiguration) {
		for (Mote mote : newConfiguration.system.motes.values()) {
			if (mote.getLinks().size() == 2) {
				mote.getLink(0).setDistribution(0);
				mote.getLink(1).setDistribution(100);
			}
		}
	}

	void composeAdaptationOptions(AdaptationOption newConfiguration) {
		// Clear the previous list of adaptation options
		currentAdaptationOptions.clear();
		List<Mote> moteOptions = new LinkedList<>();

		initializeMoteDistributions(newConfiguration);

		int initialValue = 0;
		for (Mote mote : newConfiguration.system.motes.values()) {
			// Search for the motes with 2 parents
			if (mote.getLinks().size() == 2) {
				mote = mote.getCopy();
				moteOptions.clear();

				// iterate over all the possible distribution options
				for (int i = initialValue; i <= Math.ceil(100 / (double) DISTRIBUTION_GAP); i++) {
					int distributionValue = Math.min(i * DISTRIBUTION_GAP, 100);
					mote.getLink(0).setDistribution(distributionValue);
					mote.getLink(1).setDistribution(100-distributionValue);
					moteOptions.add(mote.getCopy());
				}
				initialValue = 1;

				// add the new option to the global (feedbackloop object) adaption options for the mote
				saveAdaptationOptions(newConfiguration, moteOptions, mote.getMoteId());
			}

		}

		// Save the adaptation options in the overall set of adaptation options
		// overallAdaptationOptions.addAll(currentAdaptationOptions);

		// Update the indices of the adaptation options
		// LinkedList<AdaptationOption> options = new LinkedList<>(overallAdaptationOptions);
		for (int i = 0; i < currentAdaptationOptions.size(); i++) {
			currentAdaptationOptions.get(i).overallIndex = i;
		}
		// currentAdaptationOptions.forEach(o -> o.overallIndex = options.indexOf(o));
	}

	private void saveAdaptationOptions(AdaptationOption firstConfiguration, List<Mote> moteOptions, int moteId) {
		AdaptationOption newAdaptationOption;

		if (currentAdaptationOptions.isEmpty()) {
			// for the new options, add them to the global options
			for (int j = 0; j < moteOptions.size(); j++) {
				newAdaptationOption = firstConfiguration.getCopy();
				newAdaptationOption.system.motes.put(moteId, moteOptions.get(j));

				currentAdaptationOptions.add(newAdaptationOption);
			}

		} else {
			int size = currentAdaptationOptions.size();
			
			for (int i = 0; i < size; i++) {
				for (int j = 0; j < moteOptions.size(); j++) {
					newAdaptationOption = currentAdaptationOptions.get(i).getCopy();
					newAdaptationOption.system.motes.put(moteId, moteOptions.get(j));
					currentAdaptationOptions.add(newAdaptationOption);
				}
			}

		}
	}

	// Gets called to make a new adaption in analyse()
	private void analyzePowerSettings(AdaptationOption newConfiguration) {
		int powerSetting;
		double newSNR;

		// Iterate over the motes of the managed system (values returns a list or array with the motes)
		for (Mote mote : newConfiguration.system.motes.values()) {
			// Iterate over all the outgoing links of the mote
			for (Link link : mote.getLinks()) {

				powerSetting = link.getPower();
				newSNR = currentConfiguration.environment.getSNR(link);
				
				// find interference
				double diffSNR = getSNR(link.getSource(), link.getDestination(), powerSetting) - newSNR;

				// Calculate the most optimal power setting (higher if packet loss, lower if energy can be reserved)
				if (powerSetting < 15 && newSNR < 0 && newSNR != -50) {

					while (powerSetting < 15 && newSNR < 0) {
						newSNR = getSNR(link.getSource(), link.getDestination(), ++powerSetting) - diffSNR;
					}

				} else if (newSNR > 0 && powerSetting > 0) {
					do {
						newSNR = getSNR(link.getSource(), link.getDestination(), powerSetting - 1) - diffSNR;

						if (newSNR >= 0) {
							powerSetting--;
						}

					} while (powerSetting > 0 && newSNR >= 0);
				}

				// Adjust the powersetting of the link if it is not yet the optimal one
				if (link.getPower() != powerSetting) {
					link.setPower(powerSetting);
					currentConfiguration.environment.setSNR(link,
							getSNR(link.getSource(), link.getDestination(), powerSetting) - diffSNR);
				}
			}
		}
	}

	// when there are 2 outgoing links and they are both 100, set one to 0
	private void removePacketDuplication(AdaptationOption newConfiguration) {
		for (Mote mote : newConfiguration.system.motes.values()) {
			if (mote.getLinks().size() == 2) {
				if (mote.getLink(0).getDistribution() == 100 && mote.getLink(1).getDistribution() == 100) {
					mote.getLink(0).setDistribution(0);
					mote.getLink(1).setDistribution(100);
				}
			}
		}
	}

	double getSNR(int source, int destination, int newPowerSetting) {
		for (SNREquation equation : snrEquations) {
			if (equation.source == source && equation.destination == destination) {
				return equation.multiplier * newPowerSetting + equation.constant;
			}
		}
		throw new RuntimeException("Link not found:" + source + "-->" + destination);
	}


	boolean analysisRequired() {
		// for simulation we use adaptation after 4 periods
		// return i++%4 == 0;

		// if first time perform adaptation
		if (previousConfiguration == null)
			return true;

		Map<Integer, Mote> motes = currentConfiguration.system.motes;
		
		// Retrieve the amount of links present in the system (count links for each mote)
		final int MAX_LINKS = (int) motes.values().stream().map(o -> o.links.size()).count();
		// Check LinksSNR
		for (int j = 0; j < MAX_LINKS; j++) {
			double linksSNR = currentConfiguration.environment.linksSNR.get(j).SNR;
			if (linksSNR < SNR_BELOW_THRESHOLD || linksSNR > SNR_UPPER_THRESHOLD) {
				return true;
			}
		}

		// Check MotesTraffic
		double diff;

		for (int i : motes.keySet()) {
			diff = currentConfiguration.environment.motesLoad.get(i).load
					- previousConfiguration.environment.motesLoad.get(i).load;
			if (diff > Math.abs(diff)) {
				return true;
			}
		}

		// check qualities
		if ((currentConfiguration.qualities.packetLoss > previousConfiguration.qualities.packetLoss
				+ PACKET_LOSS_THRESHOLD)
				|| (currentConfiguration.qualities.energyConsumption > previousConfiguration.qualities.energyConsumption
						+ ENERGY_CONSUMPTION_THRESHOLD)) {
			return true;
		}

		// check if system settings are not what should be
		return !currentConfiguration.system.toString().equals(previousConfiguration.system.toString());

	}


	// The planning step of the mape loop
	// Selects "the best" addaption options of the predicted/ verified ones 
	// and plans the option to be executed
	// it assumes some options have been send, so could be dangerous but I think not
	void planning() {

		// init an adaption option
		AdaptationOption bestAdaptationOption = null;
		// AdaptationOption backUp = null;

		// TODO: What course of action if not all the goals are met?
		// TODO: Which options when using regression? -> utility function?
		for (int i = 0; i < verifiedOptions.size(); i++) {

			AdaptationOption option = verifiedOptions.get(i);
			Goal pl = goals.getPacketLossGoal();

			if (ConfigLoader.getInstance().getTaskType().equals(TaskType.PLLAMULTICLASS)) {
				Goal la = goals.getLatencyGoal();

				if (la.evaluate(option.verificationResults.latency) 
						&& pl.evaluate(option.verificationResults.packetLoss)
						&& goals.optimizeGoalEnergyConsumption(bestAdaptationOption, option)) {
					bestAdaptationOption = option;
				}

			} else {
				if (pl.evaluate(option.verificationResults.packetLoss)
						&& goals.optimizeGoalEnergyConsumption(bestAdaptationOption, option)) {
					bestAdaptationOption = option;
				}
			}

		}

		// Use the failsafe configuration if none of the options fullfill the goals
		if (bestAdaptationOption == null) {
			for (int i = 0; i < verifiedOptions.size(); i++) {
				if (goals.optimizeGoalEnergyConsumption(bestAdaptationOption, verifiedOptions.get(i))) {
					bestAdaptationOption = verifiedOptions.get(i);
				}
			}
		}

		// Go through all links and construct the steps that have to be made to change to the best adaptation option
		Link newLink, oldLink;
		for (Mote mote : bestAdaptationOption.system.motes.values()) {
			for (int i = 0; i < mote.getLinks().size(); i++) {

				// predicted mote, which will be executed
				newLink = mote.getLinks().get(i);

				// get the current link configuration. which will become the old one
				oldLink = currentConfiguration.system.motes.get(mote.moteId).getLink(i);

				if (newLink.getPower() != oldLink.getPower()) {
					// add a step/change to be executed later
					steps.add(new PlanningStep(Step.CHANGE_POWER, newLink, newLink.getPower()));
				}

				if (newLink.getDistribution() != oldLink.getDistribution()) {
					// add a step/change to be executed later
					steps.add(new PlanningStep(Step.CHANGE_DIST, newLink, newLink.getDistribution()));
				}
			}
		}

		// if there are steps to be executed, trigger execute to do them
		if (steps.size() > 0) {
			execution();
		} else {
			System.out.println(";" + System.currentTimeMillis());
		}
	}


	// gets called if there was a thresshold passed to look at new adaption,
	// and the  new adaption chosen differs from the previous one, 
	// so changes/steps have to be done
	void execution() {

		Set<Mote> motesEffected = new HashSet<>();

		// Execute the planning steps, and keep track of the motes that will need changing
		for (PlanningStep step : steps) {
			Link link = step.link;
			Mote mote = currentConfiguration.system.motes.get(link.getSource());
			
			if (step.step == Step.CHANGE_POWER) {
				findLink(mote, link.getDestination()).setPower(step.value);
			} else if (step.step == Step.CHANGE_DIST) {
				findLink(mote, link.getDestination()).setDistribution(step.value);
			}
			motesEffected.add(mote);
		}

		
		List<LinkSettings> newSettings;

		for (Mote mote : motesEffected) {

			newSettings = new LinkedList<LinkSettings>();

			for (Link link : mote.getLinks()) {

				// add a new linksettings object containing the source mote id, the dest id, the (new) power of the link,
				//  the (new) distribution of the link and the link spreading as zero to the newsetting list.
				newSettings.add(newLinkSettings(mote.getMoteId(), link.getDestination(), link.getPower(),
						link.getDistribution(), 0));
			}

			// Here you push the changes for the mote to the actual network via the effector
			effector.setMoteSettings(mote.getMoteId(), newSettings);
		}

		steps.clear();

		// print current time, to be able to tell later how long everything took
		LocalDateTime now;

		if(!human) {
			System.out.print(";" + System.currentTimeMillis() + "\n");
		} else {
			now = LocalDateTime.now();
			System.out.print("; " + String.format("%02d:%02d:%02d", 
				now.getHour(), now.getMinute(), now.getSecond()) + "\n");
		}
	}


	// return the link from mote to dest
	Link findLink(Mote mote, int dest) {
		for (Link link : mote.getLinks()) {
			if (link.getDestination() == dest)
				return link;
		}
		throw new RuntimeException(String.format("Link %d --> %d not found", mote.getMoteId(), dest));
	}


	// returns a link settings object with the given parameters as arguments.
	public LinkSettings newLinkSettings(int src, int dest, int power, int distribution, int sf) {
		LinkSettings settings = new LinkSettings();
		settings.setSrc(src);
		settings.setDest(dest);
		settings.setPowerSettings(power);
		settings.setDistributionFactor(distribution);
		settings.setSpreadingFactor(sf);
		return settings;
	}

	// dont know where this get used
	void printMote(Mote mote) {
		System.out.println(String.format("MoteId: %d, BatteryRemaining: %f, Links:%s", mote.getMoteId(),
				mote.getEnergyLevel(), getLinkString(mote.getLinks())));
	}

	
	// dont know where this gets used
	String getLinkString(List<Link> links) {
		StringBuilder strBuilder = new StringBuilder();
		for (Link link : links) {
			strBuilder.append(String.format("[Dest: %d, Power:%d, DistributionFactor:%d]", link.getDestination(),
					link.getPower(), link.getDistribution()));
		}
		return strBuilder.toString();
	}
}
