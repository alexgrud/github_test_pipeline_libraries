package com.mirantis.mk

/**
 *
 * Ceph functions
 *
 */

/**
 * Ceph health check
 *
 */
def waitForHealthy(master, target, flags=[], count=0, attempts=300) {
    def common = new com.mirantis.mk.Common()
    def salt = new com.mirantis.mk.Salt()
    // wait for healthy cluster
    while (count < attempts) {
        def health = salt.cmdRun(master, target, 'ceph health')['return'][0].values()[0]
        if (health.contains('HEALTH_OK')) {
            common.infoMsg('Cluster is healthy')
            break
        } else {
            for (flag in flags) {
                if (health.contains(flag + ' flag(s) set') && !(health.contains('down'))) {
                    common.infoMsg('Cluster is healthy')
                    return
                }
            }
        }
        common.infoMsg("Ceph health status: ${health}")
        count++
        sleep(10)
    }
}

/**
 * Ceph remove partition
 *
 */
def removePartition(master, target, partition_uuid, type='', id=-1) {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    def partition = ''
    def dev = ''
    def part_id = ''
    def lvm_enabled = salt.getPillar(master, "I@ceph:osd", "ceph:osd:lvm_enabled")['return'].first().containsValue(true)
    if ( !lvm_enabled ){
        if (type == 'lockbox') {
            try {
                // umount - partition = /dev/sdi2
                partition = salt.cmdRun(master, target, "lsblk -rp | grep -v mapper | grep ${partition_uuid} ")['return'][0].values()[0].split()[0]
                salt.cmdRun(master, target, "umount ${partition}")
            } catch (Exception e) {
                common.warningMsg(e)
            }
        } else if (type == 'data') {
            try {
                // umount - partition = /dev/sdi2
                partition = salt.cmdRun(master, target, "lsblk -rp | grep /var/lib/ceph/osd/ceph-${id}")['return'][0].values()[0].split()[0]
                salt.cmdRun(master, target, "umount ${partition}")
            } catch (Exception e) {
                common.warningMsg(e)
            }
            try {
                // partition = /dev/sdi2
                partition = salt.cmdRun(master, target, "blkid | grep ${partition_uuid} ")['return'][0].values()[0].split(":")[0]
            } catch (Exception e) {
                common.warningMsg(e)
            }
        } else {
            try {
                // partition = /dev/sdi2
                partition = salt.cmdRun(master, target, "blkid | grep ${partition_uuid} ")['return'][0].values()[0].split(":")[0]
            } catch (Exception e) {
                common.warningMsg(e)
            }
        }
        if (partition?.trim()) {
            if (partition.contains("nvme")) {
                // partition = /dev/nvme1n1p2
                // dev = /dev/nvme1n1
                dev = partition.replaceAll('p\\d+$', "")
                // part_id = 2
                part_id = partition.substring(partition.lastIndexOf("p") + 1).replaceAll("[^0-9]+", "")

            } else {
                // partition = /dev/sdi2
                // dev = /dev/sdi
                dev = partition.replaceAll('\\d+$', "")
                // part_id = 2
                part_id = partition.substring(partition.lastIndexOf("/") + 1).replaceAll("[^0-9]+", "")
            }
        }
    }
    if (lvm_enabled && type != 'lockbox') {
        salt.cmdRun(master, target, "ceph-volume lvm zap /dev/disk/by-partuuid/${partition_uuid} --destroy")
    } else if (dev != '') {
        salt.cmdRun(master, target, "parted ${dev} rm ${part_id}")
    } else {
        common.infoMsg("Did not found any device to be wiped.")
    }
    return
}
