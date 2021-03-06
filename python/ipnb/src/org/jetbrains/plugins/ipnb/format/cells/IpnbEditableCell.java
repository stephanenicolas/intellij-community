package org.jetbrains.plugins.ipnb.format.cells;

import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

public abstract class IpnbEditableCell implements IpnbCell {
  @NotNull private List<String> mySource;
  @NotNull final Map<String, Object> myMetadata;

  IpnbEditableCell(@NotNull final List<String> source, @NotNull Map<String, Object> metadata) {
    mySource = source;
    myMetadata = metadata;
  }

  @NotNull
  public List<String> getSource() {
    return mySource;
  }

  public void setSource(@NotNull final List<String> source) {
    mySource = source;
  }

  @NotNull
  public String getSourceAsString() {
    return StringUtil.join(mySource, "");
  }

  @NotNull
  public Map<String, Object> getMetadata() {
    return myMetadata;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    IpnbEditableCell cell = (IpnbEditableCell)o;

    if (!mySource.equals(cell.mySource)) return false;
    if (!myMetadata.equals(cell.myMetadata)) return false;

    return true;
  }

  @Override
  public int hashCode() {
    int result = mySource.hashCode();
    result = 31 * result + myMetadata.hashCode();
    return result;
  }
}
