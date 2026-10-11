package com.example.withmvel.model;

public class LoanDecision {

    private boolean approved;
    private double interestRate;

    // Spelled out, as javac's missing-explicit-ctor lint would want if the package were exported to every module,
    // not only to mvel2.
    public LoanDecision() {
    }

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
