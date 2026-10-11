def txt: [.runs[]?.text] | join("");
def thumb: (.thumbnail.musicThumbnailRenderer.thumbnail.thumbnails // []) | last | if . then "\(.width)x\(.height)" else "?" end;
def row:
  "  - " + ([.flexColumns[]?.musicResponsiveListItemFlexColumnRenderer.text | txt] | join(" | "))
  + " [id=" + ((.playlistItemData.videoId // .overlay.musicItemThumbnailOverlayRenderer.content.musicPlayButtonRenderer.playNavigationEndpoint.watchEndpoint.videoId // .navigationEndpoint.browseEndpoint.browseId // "-")|tostring)
  + " thumb=" + thumb + " badges=" + ([.badges[]?.musicInlineBadgeRenderer.icon.iconType] | join(",")) + "]";
[ .contents.tabbedSearchResultsRenderer.tabs[0].tabRenderer.content.sectionListRenderer.contents[]
  | if .musicCardShelfRenderer then
      .musicCardShelfRenderer as $c
      | "CARD: " + ($c.title|txt) + " / " + ($c.subtitle|txt)
        + " [tap=" + (($c.onTap.watchEndpoint.videoId // $c.onTap.browseEndpoint.browseId // "-")|tostring)
        + " type=" + (($c.onTap.watchEndpoint.watchEndpointMusicSupportedConfigs.watchEndpointMusicConfig.musicVideoType // $c.onTap.browseEndpoint.browseEndpointContextSupportedConfigs.browseEndpointContextMusicConfig.pageType // "-")|tostring)
        + " thumb=" + ($c|thumb) + "]\n"
        + ([$c.contents[]?.musicResponsiveListItemRenderer | select(.) | row] | .[0:4] | join("\n"))
    elif .musicShelfRenderer then
      .musicShelfRenderer as $s
      | "SHELF: " + ($s.title|txt) + "\n" + ([$s.contents[]?.musicResponsiveListItemRenderer | select(.) | row] | .[0:4] | join("\n"))
    else "OTHER: " + (keys|join(",")) end
] | join("\n")
