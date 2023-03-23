/* (c) 2023  Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 *
 * Original from GeoFence 3.6 under GPL 2.0 license
 */

package org.geoserver.acl.model.rules;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A class that performs some subnet calculations given a network address and a subnet mask.
 *
 * @see "http://www.faqs.org/rfcs/rfc1519.html"
 * @author rwinston@apache.org
 * @since 2.0
 */
class SubnetV4Utils {

    private static final String IP_ADDRESS = "(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})";
    private static final String SLASH_FORMAT = IP_ADDRESS + "/(\\d{1,3})";
    private static final Pattern addressPattern = Pattern.compile(IP_ADDRESS);
    private static final Pattern cidrPattern = Pattern.compile(SLASH_FORMAT);
    private static final int NBITS = 32;

    private int netmask = 0;
    private int address = 0;
    private int network = 0;
    private int broadcast = 0;

    /** Whether the broadcast/network address are included in host count */
    private boolean inclusiveHostCount = true;

    /**
     * Constructor that takes a CIDR-notation string, e.g. "192.168.0.1/16"
     *
     * @param cidrNotation A CIDR-notation string, e.g. "192.168.0.1/16"
     * @throws IllegalArgumentException if the parameter is invalid, i.e. does not match n.n.n.n/m
     *     where n=1-3 decimal digits, m = 1-3 decimal digits in range 1-32
     */
    public SubnetV4Utils(String cidrNotation) {
        calculate(cidrNotation);
    }

    /**
     * Constructor that takes a dotted decimal address and a dotted decimal mask.
     *
     * @param address An IP address, e.g. "192.168.0.1"
     * @param mask A dotted decimal netmask e.g. "255.255.0.0"
     * @throws IllegalArgumentException if the address or mask is invalid, i.e. does not match
     *     n.n.n.n where n=1-3 decimal digits and the mask is not all zeros
     */
    public SubnetV4Utils(String address, String mask) {
        calculate(toCidrNotation(address, mask));
    }

    public SubnetV4Utils(Long address, int size) {
        calculate(address, size);
    }

    /**
     * Returns <code>true</code> if the return value of {@link SubnetInfo#getAddressCount()}
     * includes the network and broadcast addresses.
     *
     * @since 2.2
     * @return true if the hostcount includes the network and broadcast addresses
     */
    public boolean isInclusiveHostCount() {
        return inclusiveHostCount;
    }

    /**
     * Set to <code>true</code> if you want the return value of {@link SubnetInfo#getAddressCount()}
     * to include the network and broadcast addresses.
     *
     * @param inclusiveHostCount true if network and broadcast addresses are to be included
     * @since 2.2
     */
    public void setInclusiveHostCount(boolean inclusiveHostCount) {
        this.inclusiveHostCount = inclusiveHostCount;
    }

    /** Convenience container for subnet summary information. */
    public final class SubnetInfo {
        /* Mask to convert unsigned int to a long (i.e. keep 32 bits) */
        private static final long UNSIGNED_INT_MASK = 0x0FFFFFFFFL;

        private SubnetInfo() {}

        private int netmask() {
            return netmask;
        }

        private int network() {
            return network;
        }

        private int address() {
            return address;
        }

        private int broadcast() {
            return broadcast;
        }

        // long versions of the values (as unsigned int) which are more suitable for range checking
        private long networkLong() {
            return network & UNSIGNED_INT_MASK;
        }

        private long broadcastLong() {
            return broadcast & UNSIGNED_INT_MASK;
        }

        private int low() {
            return (isInclusiveHostCount()
                    ? network()
                    : broadcastLong() - networkLong() > 1 ? network() + 1 : 0);
        }

        private int high() {
            return (isInclusiveHostCount()
                    ? broadcast()
                    : broadcastLong() - networkLong() > 1 ? broadcast() - 1 : 0);
        }

        /**
         * @return the number of bits of the CIDR block
         */
        public int getMaskSize() {
            return pop(netmask());
        }

        /**
         * Returns true if the parameter <code>address</code> is in the range of usable endpoint
         * addresses for this subnet. This excludes the network and broadcast adresses.
         *
         * @param address A dot-delimited IPv4 address, e.g. "192.168.0.1"
         * @return True if in range, false otherwise
         */
        public boolean isInRange(String address) {
            return isInRange(toInteger(address));
        }

        private boolean isInRange(int address) {
            long addLong = address & UNSIGNED_INT_MASK;
            long lowLong = low() & UNSIGNED_INT_MASK;
            long highLong = high() & UNSIGNED_INT_MASK;
            return addLong >= lowLong && addLong <= highLong;
        }

        public String getBroadcastAddress() {
            return format(toArray(broadcast()));
        }

        public String getNetworkAddress() {
            return format(toArray(network()));
        }

        public String getNetmask() {
            return format(toArray(netmask()));
        }

        /**
         * @return the address in dotted form
         */
        public String getAddress() {
            return format(toArray(address()));
        }

        public int getAddressAsInteger() {
            return address();
        }

        /**
         * Return the low address as a dotted IP address. Will be zero for CIDR/31 and CIDR/32 if
         * the inclusive flag is false.
         *
         * @return the IP address in dotted format, may be "0.0.0.0" if there is no valid address
         */
        public String getLowAddress() {
            return format(toArray(low()));
        }

        /**
         * Return the high address as a dotted IP address. Will be zero for CIDR/31 and CIDR/32 if
         * the inclusive flag is false.
         *
         * @return the IP address in dotted format, may be "0.0.0.0" if there is no valid address
         */
        public String getHighAddress() {
            return format(toArray(high()));
        }

        /**
         * Get the count of available addresses. Will be zero for CIDR/31 and CIDR/32 if the
         * inclusive flag is false.
         *
         * @return the count of addresses, may be zero.
         * @throws RuntimeException if the correct count is greater than {@code Integer.MAX_VALUE}
         * @deprecated use {@link #getAddressCountLong()} instead
         */
        @Deprecated
        public int getAddressCount() {
            long countLong = getAddressCountLong();
            if (countLong > Integer.MAX_VALUE) {
                throw new RuntimeException("Count is larger than an integer: " + countLong);
            }
            // N.B. cannot be negative
            return (int) countLong;
        }

        /**
         * Get the count of available addresses. Will be zero for CIDR/31 and CIDR/32 if the
         * inclusive flag is false.
         *
         * @return the count of addresses, may be zero.
         */
        public long getAddressCountLong() {
            long b = broadcastLong();
            long n = networkLong();
            long count = b - n + (isInclusiveHostCount() ? 1 : -1);
            return count < 0 ? 0 : count;
        }

        public String getCidrSignature() {
            return toCidrNotation(format(toArray(address())), format(toArray(netmask())));
        }

        public String[] getAllAddresses() {
            int ct = getAddressCount();
            String[] addresses = new String[ct];
            if (ct == 0) {
                return addresses;
            }
            for (int add = low(), j = 0; add <= high(); ++add, ++j) {
                addresses[j] = format(toArray(add));
            }
            return addresses;
        }

        /**
         * {@inheritDoc}
         *
         * @since 2.2
         */
        @Override
        public String toString() {
            final StringBuilder buf = new StringBuilder();
            buf.append("CIDR Signature:\t[")
                    .append(getCidrSignature())
                    .append("]")
                    .append(" Netmask: [")
                    .append(getNetmask())
                    .append("]\n")
                    .append("Network:\t[")
                    .append(getNetworkAddress())
                    .append("]\n")
                    .append("Broadcast:\t[")
                    .append(getBroadcastAddress())
                    .append("]\n")
                    .append("First Address:\t[")
                    .append(getLowAddress())
                    .append("]\n")
                    .append("Last Address:\t[")
                    .append(getHighAddress())
                    .append("]\n")
                    .append("# Addresses:\t[")
                    .append(getAddressCount())
                    .append("]\n");
            return buf.toString();
        }
    }

    /**
     * Return a {@link SubnetInfo} instance that contains subnet-specific statistics
     *
     * @return new instance
     */
    public final SubnetInfo getInfo() {
        return new SubnetInfo();
    }

    /*
     * Initialize the internal fields from the supplied CIDR mask
     */
    private void calculate(String mask) {
        Matcher matcher = cidrPattern.matcher(mask);

        if (matcher.matches()) {
            address = matchAddress(matcher);

            /* Create a binary netmask from the number of bits specification /x */
            int cidrPart = rangeCheck(Integer.parseInt(matcher.group(5)), 0, NBITS);
            for (int j = 0; j < cidrPart; ++j) {
                netmask |= (1 << 31 - j);
            }

            /* Calculate base network address */
            network = (address & netmask);

            /* Calculate broadcast address */
            broadcast = network | ~(netmask);
        } else {
            throw new IllegalArgumentException("Could not parse [" + mask + "]");
        }
    }

    /*
     * Initialize the internal fields from the supplied CIDR mask
     */
    private void calculate(long addr, int size) {

        address = (int) (0x0ffffffff & addr);

        /* Create a binary netmask from the number of bits specification /x */
        int cidrPart = size;
        for (int j = 0; j < cidrPart; ++j) {
            netmask |= (1 << 31 - j);
        }

        /* Calculate base network address */
        network = (address & netmask);

        /* Calculate broadcast address */
        broadcast = network | ~(netmask);
    }

    /*
     * Convert a dotted decimal format address to a packed integer format
     */
    public static int toInteger(String address) {
        Matcher matcher = addressPattern.matcher(address);
        if (matcher.matches()) {
            return matchAddress(matcher);
        } else {
            throw new IllegalArgumentException("Could not parse [" + address + "]");
        }
    }

    /*
     * Convenience method to extract the components of a dotted decimal address and
     * pack into an integer using a regex match
     */
    private static int matchAddress(Matcher matcher) {
        int addr = 0;
        for (int i = 1; i <= 4; ++i) {
            int n = (rangeCheck(Integer.parseInt(matcher.group(i)), 0, 255));
            addr |= ((n & 0xff) << 8 * (4 - i));
        }
        return addr;
    }

    public static boolean isAddress(String address) {
        return addressPattern.matcher(address).matches();
    }

    /*
     * Convert a packed integer address into a 4-element array
     */
    private int[] toArray(int val) {
        int ret[] = new int[4];
        for (int j = 3; j >= 0; --j) {
            ret[j] |= ((val >>> 8 * (3 - j)) & (0xff));
        }
        return ret;
    }

    /*
     * Convert a 4-element array into dotted decimal format
     */
    private String format(int[] octets) {
        StringBuilder str = new StringBuilder();
        for (int i = 0; i < octets.length; ++i) {
            str.append(octets[i]);
            if (i != octets.length - 1) {
                str.append(".");
            }
        }
        return str.toString();
    }

    /*
     * Convenience function to check integer boundaries.
     * Checks if a value x is in the range [begin,end].
     * Returns x if it is in range, throws an exception otherwise.
     */
    private static int rangeCheck(int value, int begin, int end) {
        if (value >= begin && value <= end) { // (begin,end]
            return value;
        }

        throw new IllegalArgumentException(
                "Value [" + value + "] not in range [" + begin + "," + end + "]");
    }

    /*
     * Count the number of 1-bits in a 32-bit integer using a divide-and-conquer strategy
     * see Hacker's Delight section 5.1
     */
    int pop(int x) {
        x = x - ((x >>> 1) & 0x55555555);
        x = (x & 0x33333333) + ((x >>> 2) & 0x33333333);
        x = (x + (x >>> 4)) & 0x0F0F0F0F;
        x = x + (x >>> 8);
        x = x + (x >>> 16);
        return x & 0x0000003F;
    }

    /* Convert two dotted decimal addresses to a single xxx.xxx.xxx.xxx/yy format
     * by counting the 1-bit population in the mask address. (It may be better to count
     * NBITS-#trailing zeroes for this case)
     */
    private String toCidrNotation(String addr, String mask) {
        return addr + "/" + pop(toInteger(mask));
    }
}
