package com.tianxian.quant.ui.community

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.tianxian.quant.R
import com.tianxian.quant.data.LocalStateRepository
import com.tianxian.quant.databinding.FragmentCommunityBinding
import com.tianxian.quant.model.Post
import com.tianxian.quant.model.PostComment
import com.tianxian.quant.ui.auth.AuthActivity
import com.tianxian.quant.ui.vip.VipActivity
import com.tianxian.quant.viewmodel.CommunityViewModel
import com.tianxian.quant.viewmodel.CommunityViewModel.Companion.VIP_CATEGORY
import kotlinx.coroutines.launch

class CommunityFragment : Fragment() {

    private var _binding: FragmentCommunityBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CommunityViewModel by viewModels()
    private lateinit var adapter: PostAdapter
    private var pendingVipPost: Post? = null
    private var pendingCommentPost: Post? = null

    private val communityVipLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val post = pendingVipPost
        pendingVipPost = null
        viewModel.refreshVipState()
        if (result.resultCode != Activity.RESULT_OK || post == null) return@registerForActivityResult
        lifecycleScope.launch {
            if (LocalStateRepository.isVipActive()) {
                showPostDetail(post)
            }
        }
    }

    private val postAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        lifecycleScope.launch {
            if (LocalStateRepository.isLoggedIn()) {
                showPostDialog()
            }
        }
    }

    private val commentAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val post = pendingCommentPost
        pendingCommentPost = null
        if (result.resultCode != Activity.RESULT_OK || post == null) return@registerForActivityResult
        lifecycleScope.launch {
            if (LocalStateRepository.isLoggedIn()) {
                showCommentDialog(post)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCommunityBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCategoryChips()
        setupRecyclerView()
        setupSwipeRefresh()
        setupFab()
        observeData()
    }

    private fun setupCategoryChips() {
        val categories = viewModel.getCategories()
        categories.forEach { category ->
            val chip = Chip(requireContext()).apply {
                text = category
                isCheckable = true
                isChecked = category == "全部"
                setOnClickListener {
                    viewModel.filterByCategory(category)
                }
            }
            binding.categoryChips.addView(chip)
        }
    }

    private fun setupRecyclerView() {
        adapter = PostAdapter(
            onItemClick = { post ->
                if (post.isVip && viewModel.isVipActive.value != true) {
                    showVipPostPreview(post)
                } else {
                    showPostDetail(post)
                }
            },
            onVipClick = { post ->
                if (post.isVip && viewModel.isVipActive.value != true) {
                    showVipPostPreview(post)
                } else {
                    openCommunitySubscription()
                }
            },
            onLikeClick = { post -> viewModel.likePost(post.id) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadPosts()
        }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)
    }

    private fun setupFab() {
        binding.fabPost.setOnClickListener {
            lifecycleScope.launch {
                if (LocalStateRepository.isLoggedIn()) {
                    showPostDialog()
                } else {
                    AlertDialog.Builder(requireContext())
                        .setTitle("请先登录")
                        .setMessage("发布帖子前需要先完成本机登录/注册。")
                        .setPositiveButton("登录/注册") { _, _ ->
                            postAuthLauncher.launch(
                                AuthActivity.createIntent(requireContext(), finishOnAuth = true)
                            )
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
    }

    private fun observeData() {
        viewModel.posts.observe(viewLifecycleOwner) { posts ->
            adapter.submitList(posts)
            binding.tvEmptyState.visibility = if (posts.isEmpty()) View.VISIBLE else View.GONE
        }
        viewModel.isVipActive.observe(viewLifecycleOwner) { active ->
            adapter.setVipActive(active)
        }
        viewModel.communityStats.observe(viewLifecycleOwner) { stats ->
            binding.tvCommunityStatus.text = buildCommunityStatus(stats)
        }
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.swipeRefresh.isRefreshing = isLoading
        }
    }

    private fun buildCommunityStatus(stats: CommunityViewModel.CommunityStats): String {
        val categoryText = if (stats.selectedCategory == "全部") "全部分类" else stats.selectedCategory
        val base = "当前：$categoryText · 展示 ${stats.visibleCount}/${stats.totalCount} 条本机帖子 · VIP内容 ${stats.vipCount} 条"
        return if (stats.lockedVipCount > 0) {
            "$base\n其中 ${stats.lockedVipCount} 条为订阅内容，可查看预览。社区仅用于研究交流和复盘记录。"
        } else {
            "$base\n社区仅用于研究交流、技术方法分享和复盘笔记。"
        }
    }

    private fun showVipPostPreview(post: Post) {
        val preview = buildString {
            append("VIP 专属内容预览\n\n")
            append("主题：${post.title}\n")
            append("分类：${post.category}\n")
            append("作者：${post.author}\n\n")
            append("开通任一 VIP 后可查看完整正文、本机评论和私密圈子交流记录。")
            append("\n\n社区内容仅用于研究交流和复盘记录，不构成投资建议。")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("VIP 专属内容")
            .setMessage(preview)
            .setPositiveButton("去订阅") { _, _ ->
                openCommunitySubscription(post)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showPostDetail(post: Post) {
        lifecycleScope.launch {
            val comments = viewModel.getComments(post.id)
            AlertDialog.Builder(requireContext())
                .setTitle(post.title)
                .setMessage(buildPostDetailMessage(post, comments))
                .setPositiveButton("评论") { _, _ -> showCommentDialog(post) }
                .setNeutralButton("点赞") { _, _ ->
                    viewModel.likePost(post.id)
                    Toast.makeText(requireContext(), "点赞成功", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun buildPostDetailMessage(post: Post, comments: List<PostComment>): String {
        val localComments = if (comments.isEmpty()) {
            "暂无本机评论。"
        } else {
            comments.take(5).joinToString("\n") { comment ->
                "${comment.author}：${comment.content}（${comment.time}）"
            }
        }
        return "${post.content}\n\n作者：${post.author}\n时间：${post.time}\n点赞：${post.likes}  评论：${post.comments}\n\n本机评论：\n$localComments"
    }

    private fun showCommentDialog(post: Post) {
        lifecycleScope.launch {
            if (!LocalStateRepository.isLoggedIn()) {
                AlertDialog.Builder(requireContext())
                    .setTitle("请先登录")
                    .setMessage("评论帖子前需要先完成本机登录/注册。")
                    .setPositiveButton("登录/注册") { _, _ ->
                        pendingCommentPost = post
                        commentAuthLauncher.launch(
                            AuthActivity.createIntent(requireContext(), finishOnAuth = true)
                        )
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@launch
            }

            val inputView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_new_comment, null)
            val etComment = inputView.findViewById<TextInputEditText>(R.id.etComment)

            AlertDialog.Builder(requireContext())
                .setTitle("发表评论")
                .setView(inputView)
                .setPositiveButton("发布") { _, _ ->
                    val content = etComment.text?.toString()?.trim().orEmpty()
                    if (content.length < 2) {
                        Toast.makeText(requireContext(), "评论至少 2 个字符", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    viewModel.addComment(post.id, content)
                    Toast.makeText(requireContext(), "评论已发布", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showPostDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_new_post, null)

        val etTitle = dialogView.findViewById<TextInputEditText>(R.id.etTitle)
        val etContent = dialogView.findViewById<TextInputEditText>(R.id.etContent)
        val etCategory = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.etCategory)
        val cbVipOnly = dialogView.findViewById<MaterialCheckBox>(R.id.cbVipOnly)
        val categories = viewModel.getCategories().filterNot { it == "全部" || it == VIP_CATEGORY }
        etCategory.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, categories)
        )
        etCategory.setText("复盘笔记", false)
        cbVipOnly.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked && viewModel.isVipActive.value != true) {
                buttonView.isChecked = false
                showVipPostGate()
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("发布帖子")
            .setView(dialogView)
            .setPositiveButton("发布") { _, _ ->
                val title = etTitle.text?.toString()?.trim() ?: ""
                val content = etContent.text?.toString()?.trim() ?: ""
                val category = etCategory.text?.toString()?.trim()
                    ?.takeIf { it in categories }
                    ?: "新手交流"

                if (title.isEmpty() || content.isEmpty()) {
                    Toast.makeText(requireContext(), "请填写标题和内容", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val isVipOnly = cbVipOnly.isChecked
                if (isVipOnly && viewModel.isVipActive.value != true) {
                    showVipPostGate()
                    return@setPositiveButton
                }

                viewModel.addPost(
                    title = title,
                    content = content,
                    category = if (isVipOnly) VIP_CATEGORY else category,
                    isVip = isVipOnly
                )
                Toast.makeText(requireContext(), "发布成功", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showVipPostGate() {
        AlertDialog.Builder(requireContext())
            .setTitle("开通 VIP")
            .setMessage("VIP 专属内容属于订阅用户私密圈子功能。开通任一 VIP 后可发布和查看完整内容。")
            .setPositiveButton("去订阅") { _, _ ->
                openCommunitySubscription()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openCommunitySubscription(post: Post? = null) {
        pendingVipPost = post
        communityVipLauncher.launch(VipActivity.createIntent(requireContext(), finishOnSuccess = true))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshVipState()
    }
}
