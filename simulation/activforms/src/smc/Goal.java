package smc;
import java.lang.IllegalArgumentException;
import mapek.AdaptationOption;


public class Goal {

    private String target;

    private String operator;

    private double tresshold;

    public Goal (String target, String operator, Double tresshold) throws IllegalArgumentException
    {
        this.target = target;

        if (operator != "<" && operator != ">" && operator != "<=" && operator != ">=" && operator != "==" && operator != "!=")
        {
            throw new IllegalArgumentException("The operator can only be < or > or <= or >= or == or != .");
        }
        this.operator = operator;
        this.tresshold = tresshold;
    }

    public String getTarget()
    {
        return this.target;
    }

    public String getOperator()
    {
        return this.operator;
    }

    public double getTresshold()
    {
        return this.tresshold;
    }

    public boolean evaluate(float value) throws IllegalArgumentException
    {
        String op = getOperator();
        double tress = getTresshold();

        if(op == "<")
        {
            return value < tress;
        }
        else if(op == ">")
        {
            return value > tress;
        }
        else if(op == "<="){
            return value <= tress;
        }
        else if(op == ">=")
        {
            return value >= tress;
        }
        else if(op == "==")
        {
            return value == tress;
        }
        else if(op == "!=")
        {
            return value != tress;
        }
        else
        {
            throw new IllegalArgumentException("Illegal operator.");
        }
    }

    /*
     * The following functions have been imported from mapek.goals because 
     * I want to make a uniform interface for goals.
     * 
     * 
     * 
     * 
     */

    // only returns true if the second argument has a lower energy consumption then the first
	// this is used when iterating throug all verified/predicted adaption options to 
	// change the best option to the second one if the energy consumption is lower
    static public boolean optimizationGoalEnergyCosnumption(AdaptationOption bestAdaptationOption, AdaptationOption adaptationOption) {
        if (bestAdaptationOption == null && adaptationOption != null)
            return true;
        return adaptationOption.verificationResults.energyConsumption < bestAdaptationOption.verificationResults.energyConsumption;
    }

    // TODO: hardcoded pl goal
    // does it satify his @#$!# hardcoded goal
    static public boolean satisfyGoalPacketLoss(AdaptationOption adaptationOption) {
        return adaptationOption.verificationResults.packetLoss < 10;
    }

    // only returns true if the second argument has a lower packet loss then the first
    // this is used when iterating throug all verified/predicted adaption options to 
    // change the best option to the second one if the packet loss is lower
    static public boolean optimizationGoalPacketLoss(AdaptationOption bestAdaptationOption,
        AdaptationOption adaptationOption) {

        return adaptationOption.verificationResults.packetLoss <= bestAdaptationOption.verificationResults.packetLoss;
    }

}