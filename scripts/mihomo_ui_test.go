package libbox

import "testing"

func TestMihomoGroups(t *testing.T) {
 groups,err:=ParseMihomoGroups(`{"proxies":{"choose":{"type":"Selector","now":"node","all":["node"]},"node":{"type":"Shadowsocks","history":[{"delay":123}]}}}`)
 if err!=nil || !groups.HasNext(){t.Fatalf("missing groups: %v",err)}
 group:=groups.Next();item:=group.GetItems().Next()
 if !group.Selectable || group.Selected!="node" || item.URLTestDelay!=123 || item.Type!="shadowsocks"{t.Fatal("incorrect group mapping")}
}

func TestMihomoConnections(t *testing.T) {
 state:=NewMihomoConnections()
 first:=`{"connections":[{"id":"one","start":"2026-11-07T00:00:00Z","upload":10,"download":20,"rule":"DomainSuffix","rulePayload":"example.com","chains":["node","choose"],"metadata":{"network":"tcp","destinationIP":"1.2.3.4","destinationPort":"443","host":"example.com"}}]}`
 events,err:=state.Update(first);if err!=nil{t.Fatal(err)}
 c:=events.Iterator().Next().Connection
 if c.Domain!="example.com" || c.Outbound!="node" || c.DownlinkTotal!=20 {t.Fatal("incorrect connection mapping")}
 events,err=state.Update(`{"connections":[]}`);if err!=nil{t.Fatal(err)}
 if events.Iterator().Next().Connection.ClosedAt==0{t.Fatal("closed connection lost")}
 if _,err=state.Update(`broken`);err==nil{t.Fatal("invalid JSON accepted")}
}
