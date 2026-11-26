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
function reserve(db, uid, eventId, pool, bank, amount, used) {
  const entry=writeBatch(db);
  const nextPool=Math.max(0,pool-amount);
  const nextBank=bank-Math.max(0,amount-pool);
  entry.update(doc(db,"teams",groupId),{pool:nextPool,bank:nextBank,lastBankEvent:eventId});
  entry.set(doc(db,"teams",groupId,"bankEvents",eventId),journal(uid,"usage",pool,bank,nextPool,nextBank));
  entry.set(doc(db,"teams",groupId,"dailyUsage",uid),{day:today,used});
  return entry.commit();
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
  const socialId = owner + "_d47a3b0a-865e-4ac8-a675-50ff0d45bc81";
  const socialEvent = db => doc(db, "teams", groupId, "socialEvents", socialId);
  const unlock = { uid: owner, kind: "unlock", pkg: "com.instagram.android", app: "Instagram",
    minutes: 7, reason: "messages", createdAt: serverTimestamp() };
  await assertFails(setDoc(socialEvent(guestDb), unlock));
  await assertFails(setDoc(socialEvent(ownerDb), { ...unlock, content: "private message" }));
  await assertFails(setDoc(socialEvent(ownerDb), { ...unlock, reason: "unknown" }));
  await assertSucceeds(setDoc(socialEvent(ownerDb), unlock));
  await assertSucceeds(getDoc(socialEvent(guestDb)));
  await assertFails(getDoc(socialEvent(outsiderDb)));
  await assertFails(updateDoc(socialEvent(ownerDb), { minutes: 9 }));
  console.log("PASS social event metadata stays in the team and is immutable");
  const requestId = owner + "_a57e8cff-f6b7-4c76-9f30-5c6003743882";
  const timeRequest = db => doc(db, "teams", groupId, "socialRequests", requestId);
  const extra = { uid: owner, device: "4a2e0201-2583-4319-b48b-f589d0e20df0",
    kind: "extra", pkg: "com.instagram.android", app: "Instagram", old: 0, minutes: 7,
    reason: "Megnézem az üzeneteimet", state: "pending", createdAt: serverTimestamp() };
  await assertFails(setDoc(timeRequest(ownerDb), { ...extra, state: "approved" }));
  await assertFails(setDoc(timeRequest(ownerDb), { ...extra, kind: "empty", minutes: 15 }));
  await assertSucceeds(setDoc(timeRequest(ownerDb), extra));
  await assertFails(getDoc(timeRequest(outsiderDb)));
  await assertFails(updateDoc(timeRequest(ownerDb), { state: "approved", decidedBy: owner, decidedAt: serverTimestamp() }));
  await assertFails(updateDoc(timeRequest(guestDb), { minutes: 60, state: "approved", decidedBy: guest, decidedAt: serverTimestamp() }));
  await assertSucceeds(updateDoc(timeRequest(guestDb), { state: "approved", decidedBy: guest, decidedAt: serverTimestamp() }));
  await assertFails(updateDoc(timeRequest(guestDb), { state: "rejected", decidedBy: guest, decidedAt: serverTimestamp() }));
  console.log("PASS team approvals require another member and preserve request details");
  await assertFails(updateDoc(groupDoc,{pool:1170}));
  await assertSucceeds(reserve(ownerDb,owner,owner+"_usage-000000000000",1200,0,30,30));
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
  const monday=new Date(today.toMillis() - ((day.getUTCDay()+6)%7)*86400000);
  const currentWeek=Timestamp.fromDate(monday);
  const boss=doc(ownerDb,"teams",groupId,"weeklyBoss","current");
  const guestBoss=doc(guestDb,"teams",groupId,"weeklyBoss","current");
  const goal=(activity,title,unit,target,done,session=0)=>({activity,title,unit,target,done,session});
  const ownerGoals=[goal("chess","Sakk","game",5,5),goal("quest_5","Séta","minute",30,30,25),
    goal("pushups","Fekvőtámasz","rep",40,40)];
  const guestGoals=[goal("quest_6","Olvasás","minute",40,25,15),goal("squats","Guggolás","rep",20,20),
    goal("custom_example","Kísérlet","minute",20,20,10)];
  const start={week:currentWeek,plans:{[owner]:ownerGoals},ready:{[owner]:true},claimedAt:null};
  await assertFails(setDoc(boss,{...start,plans:{[owner]:ownerGoals.slice(0,2)}}));
  await assertFails(setDoc(boss,{...start,plans:{[owner]:[ownerGoals[0],ownerGoals[0],ownerGoals[2]]}}));
  await assertFails(setDoc(boss,{...start,week:Timestamp.fromMillis(currentWeek.toMillis()-86400000)}));
  await assertSucceeds(setDoc(boss,start));
  await assertFails(getDoc(doc(outsiderDb,"teams",groupId,"weeklyBoss","current")));
  await assertFails(updateDoc(guestBoss,{["plans."+owner]:guestGoals}));
  await assertFails(updateDoc(guestBoss,{["plans."+guest]:guestGoals,["ready."+guest]:true}));
  await assertSucceeds(updateDoc(guestBoss,{["plans."+guest]:guestGoals}));
  const rewardId=owner+"_boss-week-0000000001";
  function claimBoss(reward){
    const batch=writeBatch(ownerDb);
    batch.update(groupDoc,{pool:1470+reward,bossClaimedAt:serverTimestamp(),lastBankEvent:rewardId});
    batch.update(boss,{claimedAt:serverTimestamp()});
    batch.set(doc(ownerDb,"teams",groupId,"bankEvents",rewardId),journal(owner,"boss",1470,0,1470+reward,0));
    return batch.commit();
  }
  await assertFails(claimBoss(3600));
  const guestDone=guestGoals.map(row=>({...row,done:row.target}));
  await assertSucceeds(updateDoc(guestBoss,{["plans."+guest]:guestDone,["ready."+guest]:true}));
  await environment.withSecurityRulesDisabled(async (admin) => {
    await updateDoc(doc(admin.firestore(),"teams",groupId),{bossClaimedAt:currentWeek});
  });
  await assertFails(claimBoss(3600));
  await environment.withSecurityRulesDisabled(async (admin) => {
    await updateDoc(doc(admin.firestore(),"teams",groupId),{bossClaimedAt:Timestamp.fromMillis(0)});
  });
  await assertFails(claimBoss(1800));
  await assertSucceeds(claimBoss(3600));
  await assertFails(claimBoss(3600));
  await assertFails(updateDoc(guestBoss,{claimedAt:null}));
  console.log("PASS weekly boss needs all three goals from each member, pays 30 minutes per member and cannot repeat");
  const giftId=guest+"_c745bd6a-3d73-475a-9baf-00281b930121";
  const giftBatch=writeBatch(guestDb);
  giftBatch.update(guestDoc,{bank:90000,lastRewardEvent:giftId,lastBankEvent:giftId});
  giftBatch.set(doc(guestDb,"teams",groupId,"events",giftId),{
    uid:guest,amount:90000,createdAt:serverTimestamp()
  });
  giftBatch.set(doc(guestDb,"teams",groupId,"bankEvents",giftId),journal(guest,"personal_bank",5070,0,5070,90000));
  await assertSucceeds(giftBatch.commit());
  console.log("PASS member can deposit more than 24 hours of virtual time into team bank");
  await assertFails(updateDoc(guestDoc,{bank:180000}));
  console.log("PASS bank cannot be increased without an idempotent matching receipt");
  await assertSucceeds(updateDoc(groupDoc,{pool:3870,bank:91200}));
  console.log("PASS member can transfer pool minutes to bank without creating time");
  await assertFails(updateDoc(groupDoc,{pool:3870,bank:94000}));
  console.log("PASS pool-to-bank transfer cannot mint extra minutes");

  const usageId=owner+"_usage-000000000001";
  const usage=doc(ownerDb,"teams",groupId,"bankEvents",usageId);
  await assertFails(updateDoc(groupDoc,{pool:3810,lastBankEvent:usageId}));
  await assertFails(setDoc(usage,journal(owner,"usage",3870,91200,3810,91200)));
  const forgedUsage=writeBatch(ownerDb);
  forgedUsage.update(groupDoc,{pool:3810,lastBankEvent:usageId});
  forgedUsage.set(usage,journal(owner,"usage",3870,91200,1900,91200));
  forgedUsage.set(doc(ownerDb,"teams",groupId,"dailyUsage",owner),{day:today,used:90});
  await assertFails(forgedUsage.commit());
  await assertSucceeds(reserve(ownerDb,owner,usageId,3870,91200,60,90));
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
  daily.update(groupDoc,{lastDay:today,pool:1200,bank:104511,lastBankEvent:dailyId});
  daily.set(doc(ownerDb,"teams",groupId,"bankEvents",dailyId),journal(owner,"daily",3810,91200,1200,104511));
  await assertSucceeds(daily.commit());
  console.log("PASS daily pool grant and bank interest can be journaled together");

  const ownerQuota=doc(ownerDb,"teams",groupId,"dailyUsage",owner);
  await assertFails(setDoc(ownerQuota,{day:today,used:0}));
  await assertFails(getDoc(doc(guestDb,"teams",groupId,"dailyUsage",owner)));
  for (let used=90; used<600;) {
    const amount=Math.min(60,600-used);
    const pool=1200-(used-90);
    used+=amount;
    await assertSucceeds(reserve(ownerDb,owner,owner+"_usage-quota-"+used,pool,104511,amount,used));
  }
  await assertFails(reserve(ownerDb,owner,owner+"_usage-quota-601",690,104511,1,601));
  await assertSucceeds(reserve(guestDb,guest,guest+"_usage-quota-060",690,104511,60,60));
  console.log("PASS each Firebase identity can reserve at most half the daily group allowance");

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
  const chatId = owner + "_aa3cd9e1-eaf1-480d-a021-1d91a8ee422c";
  const chat = db => doc(db, "teams", groupId, "chat", chatId);
  const chatText = {uid:owner, text:"Szia!", mime:"", data:"", prelude:false, createdAt:serverTimestamp()};
  await assertFails(setDoc(chat(ownerDb), {...chatText, prelude:true}));
  await assertFails(setDoc(chat(guestDb), chatText));
  await assertSucceeds(setDoc(chat(ownerDb), chatText));
  await assertSucceeds(getDoc(chat(guestDb)));
  await assertFails(getDoc(chat(outsiderDb)));
  await assertFails(updateDoc(chat(ownerDb), {text:"Átírt üzenet"}));
  const comment=doc(ownerDb,"teams",groupId,"socialRequests",requestId,"comments",chatId);
  await assertSucceeds(setDoc(comment,chatText));
  const unknownComment=doc(ownerDb,"teams",groupId,"socialRequests","missing","comments",chatId);
  await assertFails(setDoc(unknownComment,chatText));
  console.log("PASS private chat and comments enforce authors, valid attachment type, and existing requests");

  const weekly=db => doc(db,"teams",groupId,"weeklyUsage",owner);
  const usageRow={week:currentWeek,uid:owner,usage:120,shared:20,updatedAt:serverTimestamp()};
  await assertSucceeds(setDoc(weekly(ownerDb),usageRow));
  await assertSucceeds(getDoc(weekly(guestDb)));
  await assertFails(setDoc(weekly(guestDb),usageRow));
  await assertFails(getDoc(weekly(outsiderDb)));
  await assertSucceeds(deleteDoc(weekly(ownerDb)));
  console.log("PASS weekly aggregate is opt-in per identity and unreadable by outsiders");

  const friendId=owner+"_"+guest;
  const friend=db => doc(db,"friendRequests",friendId);
  await assertSucceeds(setDoc(doc(guestDb,"friendProfiles",guest),{name:"Barát"}));
  await assertSucceeds(setDoc(doc(ownerDb,"friendProfiles",owner),{name:"Gazda"}));
  await assertSucceeds(getDoc(friend(ownerDb)));
  await assertSucceeds(setDoc(friend(ownerDb),{from:owner,to:guest,state:"pending",createdAt:serverTimestamp()}));
  await assertSucceeds(getDoc(friend(guestDb)));
  await assertFails(getDoc(friend(outsiderDb)));
  await assertFails(updateDoc(friend(ownerDb),{state:"accepted"}));
  await assertSucceeds(updateDoc(friend(guestDb),{state:"accepted"}));
  await assertSucceeds(deleteDoc(friend(ownerDb)));
  console.log("PASS requests are private and only the recipient can accept");

  const publicRow=db => doc(db,"publicTeams",groupId);
  const listing={name:"Fókusz barátok",owner,updatedAt:serverTimestamp()};
  const outside="outsideruid003";
  const publicJoin={
    ["members."+outside]:profile("Új tag"), pool:increment(0), joinProof:"public:"+outside,
    bossTargetPush:increment(100), bossTargetChess:increment(10), bossTargetStudy:increment(10800)
  };
  await assertFails(updateDoc(outsiderDoc,publicJoin));
  await assertFails(setDoc(publicRow(guestDb),listing));
  await assertSucceeds(setDoc(publicRow(ownerDb),listing));
  await assertSucceeds(getDoc(publicRow(outsiderDb)));
  await assertFails(getDoc(outsiderDoc));
  const report=doc(outsiderDb,"communityReports",outside+"_c04f36bc-723e-4737-bdfd-b966c18541eb");
  await assertSucceeds(setDoc(report,{reporter:outside,team:groupId,target:groupId,kind:"team",reason:"Sértő csapatnév",createdAt:serverTimestamp()}));
  await assertSucceeds(getDoc(doc(ownerDb,"communityReports",report.id)));
  await assertFails(getDoc(doc(guestDb,"communityReports",report.id)));
  await assertFails(updateDoc(outsiderDoc,{...publicJoin,pool:increment(600)}));
  await assertSucceeds(updateDoc(outsiderDoc,publicJoin));
  await assertSucceeds(deleteDoc(publicRow(ownerDb)));
  console.log("PASS public membership only works after owner opts in; reports stay with reporter and owner");
  console.log("FIRESTORE_RULES_VALIDATED");
}finally {await environment.cleanup();}
