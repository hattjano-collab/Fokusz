import { readFileSync } from "node:fs";
import { initializeTestEnvironment, assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc, updateDoc, serverTimestamp, Timestamp, increment, writeBatch } from "firebase/firestore";

const projectId="demo-fokusz";
const owner="owneruid001";
const guest="guestuid002";
const groupId="demo1234512345";
const token="0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMN";
const environment=await initializeTestEnvironment({
  projectId,
  firestore:{host:"127.0.0.1",port:8080,rules:readFileSync("firestore.rules","utf8")}
});
const ownerDb=environment.authenticatedContext(owner).firestore();
const guestDb=environment.authenticatedContext(guest).firestore();
const outsiderDb=environment.authenticatedContext("outsideruid003").firestore();
const groupDoc=doc(ownerDb,"teams",groupId);
const guestDoc=doc(guestDb,"teams",groupId);
const outsiderDoc=doc(outsiderDb,"teams",groupId);
const day=new Date();
const today=Timestamp.fromDate(new Date(Date.UTC(day.getUTCFullYear(),day.getUTCMonth(),day.getUTCDate())));
function profile(name){
  return {name,share:false,personalLeft:-1,used:-1,push:0,chess:0,
    study:0,updatedAt:serverTimestamp()};
}
try {
  await assertSucceeds(setDoc(groupDoc,{
    ownerUid:owner,name:"Fókusz barátok",inviteKey:token,joinProof:"",
    createdAt:serverTimestamp(),lastDay:today,
    members:{[owner]:profile("Gazda")},
    pool:600,bank:0,lastRewardEvent:"",
    bossPush:0,bossChess:0,bossStudy:0,
    bossTargetPush:100,bossTargetChess:10,bossTargetStudy:10800,
    bossClaimedAt:Timestamp.fromMillis(0)
  }));
  console.log("PASS owner can create valid Spark team");
  await assertFails(getDoc(outsiderDoc));
  console.log("PASS outsiders cannot read team balances");
  await assertSucceeds(updateDoc(guestDoc,{
    ["members."+guest]:profile("Barát"),
    pool:increment(600),
    joinProof:token+":"+guest,
    bossTargetPush:increment(100),
    bossTargetChess:increment(10),
    bossTargetStudy:increment(10800)
  }));
  console.log("PASS QR secret enables authenticated remote join");
  await assertSucceeds(getDoc(guestDoc));
  await assertFails(getDoc(outsiderDoc));
  console.log("PASS membership remains private");
  await assertSucceeds(updateDoc(groupDoc,{pool:1170}));
  await assertFails(updateDoc(guestDoc,{pool:4000}));
  console.log("PASS spending is allowed, arbitrary time minting is rejected");
  const eventId=owner+"_d47a3b0a-865e-4ac8-a675-50ff0d45bc81";
  const batch=writeBatch(ownerDb);
  batch.update(groupDoc,{pool:1470,lastRewardEvent:eventId});
  batch.set(doc(ownerDb,"teams",groupId,"events",eventId),{
    uid:owner,amount:300,createdAt:serverTimestamp()
  });
  await assertSucceeds(batch.commit());
  await assertFails(updateDoc(groupDoc,{pool:1770,lastRewardEvent:eventId}));
  console.log("PASS unique quest bonus is atomic and cannot be replayed");
  await assertSucceeds(updateDoc(groupDoc,{
    ["members."+owner]:{...profile("Gazda"),push:100,chess:10,study:10800},
    bossPush:100,bossChess:10,bossStudy:10800
  }));
  await assertSucceeds(updateDoc(guestDoc,{
    ["members."+guest]:{...profile("Barát"),push:100,chess:10,study:10800},
    bossPush:200,bossChess:20,bossStudy:21600
  }));
  console.log("PASS member achievements add safely to combined team Boss Fight");
  await assertSucceeds(updateDoc(groupDoc,{
    pool:3270,
    bossClaimedAt:serverTimestamp(),
    bossTargetPush:400,bossTargetChess:40,bossTargetStudy:43200
  }));
  await assertFails(updateDoc(groupDoc,{
    pool:5070,
    bossClaimedAt:serverTimestamp(),
    bossTargetPush:600,bossTargetChess:60,bossTargetStudy:64800
  }));
  console.log("PASS combined Boss reward is collectible once with cooldown");
  console.log("FIRESTORE_RULES_VALIDATED");
}finally {await environment.cleanup();}
