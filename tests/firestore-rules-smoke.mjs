import { readFileSync } from "node:fs";
import { initializeTestEnvironment, assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc, updateDoc, deleteDoc, serverTimestamp, Timestamp, increment, writeBatch } from "firebase/firestore";

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
function journal(uid, kind, beforePool, beforeBank, afterPool, afterBank) {
  return {uid,kind,beforePool,beforeBank,afterPool,afterBank,createdAt:serverTimestamp()};
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
  batch.update(groupDoc,{pool:1470,lastRewardEvent:eventId,lastBankEvent:eventId});
  batch.set(doc(ownerDb,"teams",groupId,"events",eventId),{
    uid:owner,amount:300,createdAt:serverTimestamp()
  });
  batch.set(doc(ownerDb,"teams",groupId,"bankEvents",eventId),journal(owner,"quest",1170,0,1470,0));
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
  const giftId=guest+"_c745bd6a-3d73-475a-9baf-00281b930121";
  const giftBatch=writeBatch(guestDb);
  giftBatch.update(guestDoc,{bank:90000,lastRewardEvent:giftId,lastBankEvent:giftId});
  giftBatch.set(doc(guestDb,"teams",groupId,"events",giftId),{
    uid:guest,amount:90000,createdAt:serverTimestamp()
  });
  giftBatch.set(doc(guestDb,"teams",groupId,"bankEvents",giftId),journal(guest,"personal_bank",3270,0,3270,90000));
  await assertSucceeds(giftBatch.commit());
  console.log("PASS member can deposit more than 24 hours of virtual time into team bank");
  await assertFails(updateDoc(guestDoc,{bank:180000}));
  console.log("PASS bank cannot be increased without an idempotent matching receipt");
  await assertSucceeds(updateDoc(groupDoc,{pool:2070,bank:91200}));
  console.log("PASS member can transfer pool minutes to bank without creating time");
  await assertFails(updateDoc(groupDoc,{pool:2070,bank:94000}));
  console.log("PASS pool-to-bank transfer cannot mint extra minutes");

  const usageId=owner+"_usage-000000000001";
  const usage=doc(ownerDb,"teams",groupId,"bankEvents",usageId);
  await assertFails(updateDoc(groupDoc,{pool:2010,lastBankEvent:usageId}));
  await assertFails(setDoc(usage,journal(owner,"usage",2070,91200,2010,91200)));
  const forgedUsage=writeBatch(ownerDb);
  forgedUsage.update(groupDoc,{pool:2010,lastBankEvent:usageId});
  forgedUsage.set(usage,journal(owner,"usage",2070,91200,1900,91200));
  await assertFails(forgedUsage.commit());
  const use=writeBatch(ownerDb);
  use.update(groupDoc,{pool:2010,lastBankEvent:usageId});
  use.set(usage,journal(owner,"usage",2070,91200,2010,91200));
  await assertSucceeds(use.commit());
  await assertSucceeds(getDoc(doc(guestDb,"teams",groupId,"bankEvents",usageId)));
  await assertFails(getDoc(doc(outsiderDb,"teams",groupId,"bankEvents",usageId)));
  await assertFails(deleteDoc(usage));
  console.log("PASS journaled spending is atomic, immutable and visible only to members");

  await environment.withSecurityRulesDisabled(async (admin) => {
    await updateDoc(doc(admin.firestore(),"teams",groupId),{
      lastDay:Timestamp.fromMillis(today.toMillis() - 86400000)
    });
  });
  const dailyId=owner+"_daily-000000000001";
  const daily=writeBatch(ownerDb);
  daily.update(groupDoc,{lastDay:today,pool:1200,bank:102531,lastBankEvent:dailyId});
  daily.set(doc(ownerDb,"teams",groupId,"bankEvents",dailyId),journal(owner,"daily",2010,91200,1200,102531));
  await assertSucceeds(daily.commit());
  console.log("PASS daily pool grant and bank interest can be journaled together");

  const round=(await getDoc(groupDoc)).data().bossClaimedAt;
  const ownerTask=doc(ownerDb,"teams",groupId,"bossTasks",owner+"_chess");
  const guestTask=doc(guestDb,"teams",groupId,"bossTasks",guest+"_push");
  const commitment={uid:owner,activity:"chess",round,baseline:10,target:5,updatedAt:serverTimestamp()};
  await assertFails(setDoc(ownerTask,{...commitment,baseline:0}));
  await assertFails(setDoc(ownerTask,{...commitment,target:21}));
  await assertFails(setDoc(ownerTask,{...commitment,round:Timestamp.fromMillis(0)}));
  await assertFails(setDoc(doc(guestDb,"teams",groupId,"bossTasks",owner+"_chess"),commitment));
  await assertSucceeds(setDoc(ownerTask,commitment));
  await assertSucceeds(setDoc(guestTask,{uid:guest,activity:"push",round,baseline:100,target:40,updatedAt:serverTimestamp()}));
  await assertSucceeds(getDoc(doc(guestDb,"teams",groupId,"bossTasks",owner+"_chess")));
  await assertFails(getDoc(doc(outsiderDb,"teams",groupId,"bossTasks",owner+"_chess")));
  await assertFails(deleteDoc(doc(guestDb,"teams",groupId,"bossTasks",owner+"_chess")));
  await assertSucceeds(deleteDoc(ownerTask));
  console.log("PASS members choose and share their own boss commitments without changing team totals");
  console.log("FIRESTORE_RULES_VALIDATED");
}finally {await environment.cleanup();}
