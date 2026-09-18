package com.example.withoutmvel.model;

/** An output object the engine's default writer sets the properties an action returned on. */
public class LoanDecision {

    private boolean approved;
    private double interestRate;

    public boolean isApproved() {
        return approved;
    }

    public void setApproved(boolean approved) {
        this.approved = approved;
    }

    public double getInterestRate() {
        return interestRate;
    }

    public void setInterestRate(double interestRate) {
        this.interestRate = interestRate;
    }

    @Override
    public String toString() {
        return "LoanDecision[approved=" + approved + ", interestRate=" + interestRate + "]";
    }
}
