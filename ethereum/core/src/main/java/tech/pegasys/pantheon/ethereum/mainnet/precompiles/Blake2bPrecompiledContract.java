package tech.pegasys.pantheon.ethereum.mainnet.precompiles;

import tech.pegasys.pantheon.crypto.Hash;
import tech.pegasys.pantheon.ethereum.core.Gas;
import tech.pegasys.pantheon.ethereum.mainnet.AbstractPrecompiledContract;
import tech.pegasys.pantheon.ethereum.vm.GasCalculator;
import tech.pegasys.pantheon.ethereum.vm.MessageFrame;
import tech.pegasys.pantheon.util.bytes.Bytes32;
import tech.pegasys.pantheon.util.bytes.BytesValue;

public class Blake2bPrecompiledContract extends AbstractPrecompiledContract { // TODO register this

    public Blake2bPrecompiledContract(final GasCalculator gasCalculator) {
        super("Blake2b", gasCalculator);
    }

    @Override
    public Gas gasRequirement(BytesValue input) {
        return gasCalculator().blake2bPrecompiledContractGasCost(input);
    }

    @Override
    public BytesValue compute(BytesValue input, MessageFrame messageFrame) {
        return Bytes32.leftPad(Hash.Blake2b(input));
    }
}
