package com.yourorg.omp.ide;

import com.yourorg.omp.config.OmpProperties;
import com.yourorg.omp.entity.SessionMeta;
import com.yourorg.omp.entity.User;
import com.yourorg.omp.repo.UserRepository;
import com.yourorg.omp.security.CurrentUser;
import com.yourorg.omp.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * IDE（code-server / openvscode-server）集成服务。
 * 基于会话定位工作区目录，构建浏览器直连的 IDE URL。
 */
@Service
public class IdeService {

    private static final Logger log = LoggerFactory.getLogger(IdeService.class);

    /** 路径安全标识符：仅允许字母、数字、横线 */
    private static final Pattern PATH_ID_PATTERN = Pattern.compile("^[A-Za-z0-9-]+$");

    private final OmpProperties props;
    private final CurrentUser currentUser;
    private final UserRepository users;
    private final SessionManager sessions;
    private final WorkspaceSigner signer;

    public IdeService(OmpProperties props, CurrentUser currentUser,
                       UserRepository users, SessionManager sessions) {
        this.props = props;
        this.currentUser = currentUser;
        this.users = users;
        this.sessions = sessions;
        this.signer = new WorkspaceSigner(props.ide().signingKey());
    }

    /**
     * 基于会话构建 IDE 直连 URL。
     * 会话记录中带有 userId 和 repoId，直接据此定位工作区目录，
     * 不依赖 repos 表是否存在记录（更健壮）。
     *
     * @return code-server / openvscode-server 的完整 URL，
     *         如 {@code http://localhost:3000/?folder=/tmp/omp/workspaces/admin/my-project}
     */
    public String buildIdeUrlForSession(String sessionId) {
        SessionMeta session = sessions.findScoped(sessionId, currentUser.scope())
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或无访问权限"));

        Long ownerId = session.getUserId();
        String repoId = session.getRepoId();

        User owner = users.findById(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("会话归属用户不存在: " + ownerId));

        String username = owner.getUsername();
        validatePathId(username, "用户名");
        validatePathId(repoId, "仓库标识");

        Path workspacePath = resolveWorkspacePath(username, repoId);

        String base = props.ide().publicBaseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String folder = workspacePath.toString();
        String param = signer.enabled() ? "folder=" + folder + signer.signParam(folder) : "folder=" + folder;
        return base + "/?" + param;
    }

    /**
     * 根据 username 和 repoId 解析磁盘上的工作区绝对路径。
     * 返回 {@code {workspacesRoot}/{username}/{repoId}}。
     *
     * @throws IllegalArgumentException 如果参数包含非法字符或越界
     */
    private Path resolveWorkspacePath(String username, String repoId) {
        Path p = props.workspacesRoot().resolve(username).resolve(repoId).normalize();
        if (!p.startsWith(props.workspacesRoot())) {
            throw new IllegalArgumentException("工作区路径超出根目录范围");
        }
        return p;
    }

    private void validatePathId(String value, String label) {
        if (value == null || value.isBlank() || !PATH_ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(label + "包含非法字符: " + value);
        }
    }
}
