package tech.pegasys.pantheon.ethereum.mainnet;

import tech.pegasys.pantheon.ethereum.core.Gas;
import tech.pegasys.pantheon.util.bytes.BytesValue;

public class IstanbulGasCalculator extends ConstantinopleFixGasCalculator {

    private static final Gas BLAKE2B_COST = Gas.of(0L); // TODO find the correct amount

    @Override
    public Gas blake2bPrecompiledContractGasCost(BytesValue input) {
        return BLAKE2B_COST; // TODO calculate correctly according to EIP
    }
}
