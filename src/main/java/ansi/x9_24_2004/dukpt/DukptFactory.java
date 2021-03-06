package ansi.x9_24_2004.dukpt;

import ansi.x9_24_2004.encryption.Des;
import ansi.x9_24_2004.encryption.TripleDes;
import ansi.x9_24_2004.utils.ByteArrayUtils;
import ansi.x9_24_2004.utils.CustomBitSet;

/**
 * ANSI X9.24 version 2009 DUKPT key derivation.
 *
 * All core key derivation functions from:
 * - Java Triple DES DUKPT Library by Software Verde (authors: Andrew Groot and Josh Green).
 *   https://github.com/SoftwareVerde/java-dukpt (MIT license)
 *
 * Algorithm overview and pseudo-code:
 * - DUKPT Within a Point of Sale Environment: How Does It Work? ().
 *   https://www.futurex.com/blog/dukpt-in-point-of-sale-how-does-it-work
 * - DUKPT Explained with examples (Arthur Van Der Merwe).
 *   https://arthurvandermerwe.com/2015/05/30/dukpt-explained-with-examples/
 * - How to decrypt card data.
 *   https://idtechproducts.com/technical-post/how-to-decrypt-credit-card-data-part-ii/
 * - "Key" to Secure Data - P2PE - Derived Unique Key Per Transaction (Andrew McKenna).
 *   https://www.foregenix.com/blog/p2pe-derived-unique-key-per-transaction-dukpt
 * - IFSF Recommended Security Standards v2.00.
 */
public class DukptFactory {

    private final Des des;
    private final TripleDes tripleDes;

    public DukptFactory(final Des des, final TripleDes tripleDes) {
        this.des = des;
        this.tripleDes = tripleDes;
    }

    public CustomBitSet computeKey(final CustomBitSet bdk, final CustomBitSet ksn, final IfsfKeyMask ifsfKeyMask) {
        final CustomBitSet ipek = getIpek(bdk, ksn);
        final CustomBitSet transactionKey = deriveSessionKey(ipek, ksn);

        transactionKey.xor(ifsfKeyMask.value());

        return transactionKey;
    }

    // See: https://github.com/SoftwareVerde/java-dukpt
    public CustomBitSet computeAnsiX924version2009DataKey(final CustomBitSet bdk,
                                                          final CustomBitSet ksn) {
        final CustomBitSet derivedKey = computeKey(bdk, ksn, IfsfKeyMask.REQUEST_DATA_MASK);

        byte[] firstComponent = tripleDes.encrypt(derivedKey, derivedKey.get(0, 64).toByteArray());
        byte[] secondComponent = tripleDes.encrypt(derivedKey, derivedKey.get(64, 128).toByteArray());

        return new CustomBitSet(ByteArrayUtils.concat(firstComponent, secondComponent));
    }

    CustomBitSet getIpek(final CustomBitSet key, final CustomBitSet ksn) {
        byte[][] ipek = new byte[2][];
        CustomBitSet keyRegister = key.get(0, key.size());
        CustomBitSet data = ksn.get(0, ksn.size());
        data.clear(59, 80);

        ipek[0] = tripleDes.encrypt(keyRegister, data.get(0, 64).toByteArray());
        keyRegister.xor(IfsfKeyMask.KEY_REGISTER_BITMASK.value());
        ipek[1] = tripleDes.encrypt(keyRegister, data.get(0, 64).toByteArray());

        return new CustomBitSet(ByteArrayUtils.concat(ipek[0], ipek[1]));
    }

    // See: https://github.com/SoftwareVerde/java-dukpt
    CustomBitSet deriveSessionKey(final CustomBitSet ipek, final CustomBitSet ksn) {
        final CustomBitSet counter = ksn.get(0, ksn.size());
        CustomBitSet transactionKey = (CustomBitSet) ipek.clone();

        counter.clear(59, ksn.size());
        for (int i = 59; i < ksn.size(); i++) {
            if (ksn.get(i)) {
                counter.set(i);
                transactionKey = generateTransactionKey(transactionKey, counter.get(16, 80));
            }
        }

        return transactionKey;
    }

    // See: https://github.com/SoftwareVerde/java-dukpt
    CustomBitSet generateTransactionKey(final CustomBitSet transactionKey, final CustomBitSet data) {
        CustomBitSet keyReg = transactionKey.get(0, transactionKey.size());
        CustomBitSet reg1 = data.get(0, data.size());
        // step 1: Crypto Register-1 XORed with the right half of the Key Register goes to Crypto Register-2.
        CustomBitSet reg2 = reg1.get(0, 64); // reg2 is being used like a temp here
        reg2.xor(keyReg.get(64, 128));   // and here, too, kind of
        // step 2: Crypto Register-2 DEA-encrypted using, as the key, the left half of the Key Register goes to Crypto Register-2
        reg2 = new CustomBitSet(des.encrypt(keyReg.get(0, 64), reg2.toByteArray()));
        // step 3: Crypto Register-2 XORed with the right half of the Key Register goes to Crypto Register-2
        reg2.xor(keyReg.get(64, 128));
        // done messing with reg2

        // step 4: XOR the Key Register with hexadecimal C0C0 C0C0 0000 0000 C0C0 C0C0 0000 0000
        keyReg.xor(IfsfKeyMask.KEY_REGISTER_BITMASK.value());
        // step 5: Crypto Register-1 XORed with the right half of the Key Register goes to Crypto Register-1
        reg1.xor(keyReg.get(64, 128));
        // step 6: Crypto Register-1 DEA-encrypted using, as the key, the left half of the Key Register goes to Crypto Register-1
        reg1 = new CustomBitSet(des.encrypt(keyReg.get(0, 64), reg1.toByteArray()));
        // step 7: Crypto Register-1 XORed with the right half of the Key Register goes to Crypto Register-1
        reg1.xor(keyReg.get(64, 128));
        // done

        final byte[] reg1b = reg1.toByteArray();
        final byte[] reg2b = reg2.toByteArray();

        return new CustomBitSet(ByteArrayUtils.concat(reg1b, reg2b));
    }

}
