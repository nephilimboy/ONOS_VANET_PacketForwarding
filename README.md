# ONOS_VANET_PacketForwarding

The Car C1 is pinging 10.10.0.2 in Zone1 while moving thowards Zone2. After reaching zone2, SDN controller (ONOS) adds new rules to the zone2's edge switch (swith 41a7) which forward the packets (ping packets) to the new destination (10.10.0.3). This change is transparent to the user (Car C1) so C1 is still pinging 10.10.0.2 but the packets are forwarded to 10.10.0.3 . 

## OVS actions
    - mod_dl_src / mod_nw_src​
    - mod_dl_dst / mod_nw_dst

## Youtube Link
https://youtu.be/ddAzWaLzMUg