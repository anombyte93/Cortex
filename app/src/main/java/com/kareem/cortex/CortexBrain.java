package com.kareem.cortex;

public interface CortexBrain {

    CognitiveResult classify(
            CognitiveInput input
    ) throws BrainException;
}
