package libbox

import (
 "encoding/json"
 "fmt"
 "sort"
 "strings"
 "time"
)

type mihomoProxy struct {
 Type string `json:"type"`
 Now string `json:"now"`
 All []string `json:"all"`
 History []struct { Delay int32 `json:"delay"` } `json:"history"`
}

func mihomoType(value string) string {
 switch strings.ToLower(value) {
 case "selector": return "selector"
 case "urltest", "fallback", "loadbalance": return "urltest"
 case "shadowsocks": return "shadowsocks"
 default: return strings.ToLower(value)
 }
}

// ParseMihomoGroups adapts controller snapshots to the existing Android UI model.
func ParseMihomoGroups(content string) (OutboundGroupIterator, error) {
 var response struct { Proxies map[string]mihomoProxy `json:"proxies"` }
 if err := json.Unmarshal([]byte(content), &response); err != nil { return nil, err }
 names := make([]string,0,len(response.Proxies))
 for name := range response.Proxies { names=append(names,name) }; sort.Strings(names)
 groups := make([]*OutboundGroup,0)
 for _,name := range names {
  proxy := response.Proxies[name]; if proxy.All == nil || name=="GLOBAL" { continue }
  group := &OutboundGroup{Tag:name,Type:mihomoType(proxy.Type),Selectable:proxy.Type=="Selector",Selected:proxy.Now,IsExpand:true}
  for _,tag := range proxy.All {
   item:=response.Proxies[tag]; delay:=int32(0)
   if len(item.History)>0 { delay=item.History[len(item.History)-1].Delay }
   group.itemList=append(group.itemList,&OutboundGroupItem{Tag:tag,Type:mihomoType(item.Type),URLTestTime:time.Now().Unix(),URLTestDelay:delay})
  }; groups=append(groups,group)
 }
 return newIterator(groups),nil
}

type MihomoConnections struct { previous map[string]*Connection }
func NewMihomoConnections() *MihomoConnections { return &MihomoConnections{previous:make(map[string]*Connection)} }
func (s *MihomoConnections) Update(content string) (*ConnectionEvents,error) {
 var response struct { Connections []struct {
  ID string `json:"id"`; Start time.Time `json:"start"`; Upload int64 `json:"upload"`; Download int64 `json:"download"`
  Rule string `json:"rule"`; RulePayload string `json:"rulePayload"`; Chains []string `json:"chains"`
  Metadata struct { Network string `json:"network"`; Type string `json:"type"`; SourceIP string `json:"sourceIP"`; SourcePort string `json:"sourcePort"`; DestinationIP string `json:"destinationIP"`; DestinationPort string `json:"destinationPort"`; Host string `json:"host"`; Process string `json:"process"`; ProcessPath string `json:"processPath"` } `json:"metadata"`
 } `json:"connections"` }
 if err:=json.Unmarshal([]byte(content),&response); err!=nil{return nil,err}
 now:=time.Now().UnixMilli(); active:=make(map[string]bool)
 for _,item:=range response.Connections {
  m:=item.Metadata; previous:=s.previous[item.ID]; up,down:=int64(0),int64(0)
  if previous!=nil { up=item.Upload-previous.UplinkTotal; down=item.Download-previous.DownlinkTotal }
  outbound:="";if len(item.Chains)>0{outbound=item.Chains[0]}
  destination:=fmt.Sprintf("%s:%s",m.DestinationIP,m.DestinationPort)
  if strings.Contains(m.DestinationIP,":"){destination=fmt.Sprintf("[%s]:%s",m.DestinationIP,m.DestinationPort)}
  c:=&Connection{ID:item.ID,Inbound:"mihomo",InboundType:strings.ToLower(m.Type),Network:m.Network,Source:m.SourceIP+":"+m.SourcePort,Destination:destination,Domain:m.Host,CreatedAt:item.Start.UnixMilli(),Uplink:up,Downlink:down,UplinkTotal:item.Upload,DownlinkTotal:item.Download,Rule:item.Rule+" "+item.RulePayload,Outbound:outbound,OutboundType:"mihomo",chainList:item.Chains}
  if m.ProcessPath!="" {c.ProcessInfo=&ProcessInfo{ProcessPath:m.ProcessPath,UserName:m.Process}}
  s.previous[item.ID]=c;active[item.ID]=true
 }
 events:=&ConnectionEvents{Reset:true}
 for id,c:=range s.previous {
  if !active[id] && c.ClosedAt==0 {c.ClosedAt=now;c.Uplink=0;c.Downlink=0}
  if c.ClosedAt>0 && now-c.ClosedAt>closedConnectionMaxAge {delete(s.previous,id);continue}
  events.events=append(events.events,&ConnectionEvent{Type:ConnectionEventNew,ID:id,Connection:c})
 }
 return events,nil
}
