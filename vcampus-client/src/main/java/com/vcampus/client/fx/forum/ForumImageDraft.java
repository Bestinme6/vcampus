package com.vcampus.client.fx.forum;

import javafx.scene.image.Image;
import java.nio.file.Path;

public record ForumImageDraft(Path path,String displayName,long byteSize,String mimeType,Image preview){ }
